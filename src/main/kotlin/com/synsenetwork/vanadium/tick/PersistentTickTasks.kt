package com.synsenetwork.vanadium.tick

import com.synsenetwork.vanadium.diagnostics.TickProfile
import com.synsenetwork.vanadium.diagnostics.WaveDiagnostics
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.LongSupplier
import kotlin.math.ceil

/**
 * One persistent task per member of a tick list, kept across ticks, so that dispatching an unchanged
 * list costs nothing per member. The list ([TickList]) reports membership changes ([changed]) and possible
 * serial-rule changes ([reclassify]) from any thread; they take effect at the next [dispatch], which
 * runs on the server thread between waves. That matches vanilla's iteration over a snapshot: a member
 * added during a tick first ticks next tick, and one removed during a tick may still be visited by it
 * (vanilla's body returns early for removed entities).
 *
 * Classification is cached per task: the fixed part ([TickList.fixedSerial]) is recomputed when the
 * `rulesKey` passed to [dispatch] changes; the changing part ([TickList.changingSerial]) for members reported
 * through [reclassify] and their owners up the [TickList.owner] chain, and at every dispatch for members that
 * are serial only through it, so they return to the wave once it no longer holds.
 *
 * The wave is ordered by expected cost: kinds ([TickList.kind]) run costliest member first ([Kind.longestNanos]),
 * each in member order, cut into [Group]s of about equal expected cost, about [GROUPS_PER_PARTICIPANT] per
 * participant. Groups time themselves; that is the only measurement. Groups are cut again, without visiting
 * members, only when a kind's ideal size drifts; members are reordered only when the list is rebuilt or a kind
 * becomes clearly costlier than one ahead of it. Correctness never depends on the order. With homes ([WaveHomes]),
 * each kind is also cut into equal slices of member order, one per home. ARCHITECTURE.md ("Flat entity wave")
 * explains why.
 *
 * Each [dispatch] runs in three steps: assembly ([plan]: order and groups, from the learned costs), the wave, and
 * learning ([learn]: kind costs and [longestNanos], from the groups' times). Profile-only accounting
 * ([recordLastWave]) reads the same times afterwards and feeds nothing back.
 *
 * Not thread-safe apart from [changed] and [reclassify]; [dispatch] and [stop] belong to the server thread.
 */
class PersistentTickTasks<E : Any> internal constructor(private val list: TickList<E>, private val clock: LongSupplier) {
    constructor(list: TickList<E>) : this(list, LongSupplier(System::nanoTime))

    /** The tick list the tasks serve: its members, their rules, and how its waves run. */
    interface TickList<E> {
        /** Unique while the member exists. */
        fun id(item: E): Int

        /** Whether the item is currently in the tick list. Read at dispatch, never during a wave. */
        fun isMember(item: E): Boolean

        /** Serial for reasons that change only with [dispatch]'s rules key. */
        fun fixedSerial(item: E): Boolean

        /** Serial for reasons that change only after a [reclassify] report for the item or one it owns. */
        fun changingSerial(item: E): Boolean

        /** The member whose classification depends on this one's (a vehicle), or null. */
        fun owner(item: E): E?

        fun label(item: E): String

        /** What predicts the member's tick cost (for entities, the type); compared with equals, fixed for the member's life. */
        fun kind(item: E): Any

        /** Runs one member's wave task with this tick's body. */
        fun tick(item: E, body: Consumer<E>)

        /** Visits every current member: the rescan when tracking (re)starts. */
        fun forEachMember(action: Consumer<E>)

        /**
         * The threads, the caller included, to run a wave of [members] members with: they size the groups.
         * [longestNanos] is the wave's expected longest group ([PersistentTickTasks.longestNanos]), 0 before any was measured.
         */
        fun participants(members: Int, longestNanos: Double): Int

        /**
         * Runs a wave's [groups], which tick [members] members between them, as one flat wave of [participants] threads;
         * [homes] says which participant runs which groups, or is null. [groups] stays unchanged until the next dispatch.
         */
        fun runWave(groups: List<Runnable>, members: Int, participants: Int, homes: WaveHomes?)
    }

    inner class Task internal constructor(@JvmField val item: E, internal val kind: Kind) : Runnable {
        internal var slot = -1
        internal var fixed = false
        internal var serial = false
        private var named: Runnable? = null

        override fun run() = list.tick(item, checkNotNull(body) { "member task outside a dispatch" })

        internal fun waveTask(labels: Boolean): Runnable {
            if (!labels) return this
            var task = named
            if (task == null) {
                task = WaveDiagnostics.NamedTask(list.label(item), this)
                named = task
            }
            return task
        }
    }

    /** Members sharing a [TickList.kind]: their place in [order] and their measured cost. Server thread only. */
    internal class Kind(val key: Any) {
        /** Smoothed wall time per member tick; 0 until measured. */
        var nanosPerTask = 0.0

        /**
         * Smoothed cost of the kind's costliest member: per wave, the largest per-member time of its groups (a costly
         * kind's groups hold one member each, so that is its longest tick). Ranks the kinds; 0 until measured.
         */
        var longestNanos = 0.0
        var start = 0
        var count = 0
        internal var rank = 0
        internal var members = 0

        /** Members per group. */
        internal var perGroup = 0

        fun estimate(fallback: Double): Double = if (nanosPerTask > 0) nanosPerTask else fallback
    }

    /**
     * One claim of the wave: members [start] until [end] of [order], all of one kind. Runs every member even
     * when one throws (as the pool does for separate tasks) and rethrows the first failure afterwards. Its
     * time is the wave's only measurement ([timed]); the groups themselves are only read during a wave.
     */
    internal inner class Group : Runnable {
        var index = 0
        var start = 0
        var end = 0

        override fun run() {
            val members = order
            val began = clock.asLong
            var failure: Throwable? = null
            for (i in start until end) {
                try {
                    members[i]!!.run()
                } catch (t: Throwable) {
                    if (failure == null) failure = t
                }
            }
            timed(index, began, clock.asLong)
            if (failure != null) throw failure
        }
    }

    private val changes = ConcurrentLinkedQueue<E>()
    private val reclassifications = ConcurrentLinkedQueue<E>()
    private val pending = AtomicInteger()

    /** Hooks queue nothing until the first dispatch and after [stop] or an overflow; dispatch then rescans. */
    @Volatile
    private var tracking = false

    private val byId = Int2ObjectOpenHashMap<Task>()
    private var all = arrayOfNulls<Any>(INITIAL_CAPACITY)
    private var size = 0
    private val serialTasks = ArrayList<Task>()
    private val kinds = HashMap<Any, Kind>()

    /** Kinds with wave members, in wave order; their segments of [order] are contiguous and in this order. */
    private val ranked = ArrayList<Kind>()

    // Assembly ([plan]): the wave's order and its groups.

    /** The wave's members (or their labelled wrappers), by kind; [waveSize] are used. */
    private var order = arrayOfNulls<Runnable>(INITIAL_CAPACITY)
    private var spare = arrayOfNulls<Runnable>(0)
    private var waveSize = 0
    private val groupPool = ArrayList<Group>()
    private val wave = ArrayList<Runnable>()
    private var sizes = IntArray(16)

    /** Each group's member count and kind (its rank in [ranked]), by group index. */
    private var groupMembers = IntArray(INITIAL_GROUPS)
    private var groupKinds = IntArray(INITIAL_GROUPS)

    // Measurement ([timed]): dense arrays by group index, which the server thread reads after the wave without
    // touching group objects.

    /** Each group's time: the input of [learn], and the profile's. */
    private var groupNanos = LongArray(INITIAL_GROUPS)

    /** Profile only ([recordLastWave]): when each group began and ended. */
    private var groupBegins = LongArray(INITIAL_GROUPS)
    private var groupEnds = LongArray(INITIAL_GROUPS)

    // Learning ([learn]): scratch per kind rank.
    private var kindNanos = LongArray(0)
    private var kindCostliest = DoubleArray(0)

    /**
     * Homes of the wave ([WaveHomes]), 0 for none: each kind's members are cut into this many equal slices, and a
     * group never crosses a slice. Slice h of every kind is home h.
     */
    private var homeCount = 0
    private val homes = WaveHomes(1)

    /** Groups made so far by the [plan] in progress. */
    private var used = 0

    /**
     * The wave's expected longest group: smoothed per wave, rising slowly and falling fast, so a long group that
     * stops recurring soon stops limiting the wave's participants. 0 until measured.
     */
    var longestNanos = 0.0
        private set

    /** Whether [wave]'s groups match [order] and the kinds' sizes; cleared when either changes. */
    private var planned = false
    private var plannedParticipants = 0

    /** Profile only ([recordLastWave]): the dispatching thread and when its wave started. */
    private var caller: Thread? = null
    private var waveStarted = 0L

    /** Profile only: the end of the last group the dispatching thread ran; written only by that thread. */
    private var callerDone = 0L
    private var rebuild = true
    private var rulesKey: Any? = UNSET
    private var labels = false
    private var body: Consumer<E>? = null

    /** Membership of [item] may have changed. Any thread. */
    fun changed(item: E) {
        if (tracking) offer(changes, item)
    }

    /** The serial rule of [item] or of an owner may have changed. Any thread. */
    fun reclassify(item: E) {
        if (tracking) offer(reclassifications, item)
    }

    private fun offer(queue: ConcurrentLinkedQueue<E>, item: E) {
        queue.offer(item)
        if (pending.incrementAndGet() > MAX_PENDING) tracking = false // Next dispatch rescans; drop the backlog there.
    }

    /**
     * Brings the tasks up to date, runs serial members through [body] on the calling thread in list order, then
     * runs the rest as one wave ([TickList.runWave]) on the threads [TickList.participants] chooses.
     *
     * With [homes] homes ([WaveHomes]; 0 or 1 for none) each participant runs the same slice of every kind from wave
     * to wave, with kinds still claimed costliest first. Only a wave that gets [homes] participants has homes. One
     * with fewer is bounded by its longest group; there homes measured 5% slower in a 600-mob fight and changed no
     * cache traffic, and slots would own a varying number of homes.
     */
    fun dispatch(rulesKey: Any?, labels: Boolean, homes: Int, body: Consumer<E>) {
        update(rulesKey, labels)
        // Assembly: participants from the expected longest group, then order and groups from the learned costs.
        val threads = if (waveSize == 0) 1 else maxOf(1, list.participants(waveSize, longestNanos))
        val homeCount = if (homes > 1 && threads >= homes) homes else 0
        if (homeCount != this.homeCount) {
            this.homeCount = homeCount
            planned = false
        }
        plan(threads)
        this.body = body
        try {
            for (i in serialTasks.indices) body.accept(serialTasks[i].item)
            caller = Thread.currentThread()
            waveStarted = clock.asLong
            callerDone = waveStarted
            list.runWave(wave, waveSize, threads, if (homeCount > 0 && wave.isNotEmpty()) this.homes else null)
            learn()
        } finally {
            this.body = null
            caller = null
        }
    }

    /** Drops all state: for the disabled path, where the tick list is iterated directly. */
    fun stop() {
        if (!tracking && size == 0) return
        tracking = false
        clear()
    }

    private fun update(rulesKey: Any?, labels: Boolean) {
        if (!tracking) {
            // Tracking first, so a change during the scan is queued and applied after it.
            clear()
            tracking = true
            list.forEachMember { sync(it) }
        }
        drain(changes) { sync(it) }
        if (rulesKey !== this.rulesKey) {
            this.rulesKey = rulesKey
            for (i in 0 until size) {
                val task = taskAt(i) ?: continue
                task.fixed = list.fixedSerial(task.item)
                classify(task)
            }
        }
        drain(reclassifications) { item ->
            var current: E? = item
            var depth = 0
            while (current != null && depth++ < MAX_OWNER_DEPTH) {
                val task = byId.get(list.id(current))
                if (task != null && task.item === current) classify(task)
                current = list.owner(current)
            }
        }
        for (i in serialTasks.indices) {
            val task = serialTasks[i]
            if (!task.fixed && task.slot >= 0) classify(task)
        }
        if (labels != this.labels) {
            this.labels = labels
            rebuild = true
        }
        if (rebuild) rebuildLists()
    }

    private inline fun drain(queue: ConcurrentLinkedQueue<E>, action: (E) -> Unit) {
        while (true) {
            val item = queue.poll() ?: return
            pending.decrementAndGet()
            action(item)
        }
    }

    private fun sync(item: E) {
        val id = list.id(item)
        val task = byId.get(id)
        if (list.isMember(item)) {
            if (task != null && task.item === item) return
            if (task != null) remove(task)
            add(item, id)
        } else if (task != null && task.item === item) {
            remove(task)
        }
    }

    private fun add(item: E, id: Int) {
        val key = list.kind(item)
        val task = Task(item, kinds.getOrPut(key) { Kind(key) })
        task.fixed = list.fixedSerial(item)
        task.serial = task.fixed || list.changingSerial(item)
        if (size == all.size) all = all.copyOf(size * 2)
        task.slot = size
        all[size++] = task
        byId.put(id, task)
        rebuild = true
    }

    private fun remove(task: Task) {
        all[task.slot] = null
        task.slot = -1
        byId.remove(list.id(task.item))
        rebuild = true
    }

    private fun classify(task: Task) {
        val serial = task.fixed || list.changingSerial(task.item)
        if (serial != task.serial) {
            task.serial = serial
            rebuild = true
        }
    }

    /**
     * Compacts the member array in place, refills the serial list in member order and the wave's [order] by kind
     * (costliest first, each in member order), and forgets kinds without members.
     */
    private fun rebuildLists() {
        serialTasks.clear()
        for (kind in kinds.values) {
            kind.count = 0
            kind.members = 0
        }
        var kept = 0
        for (i in 0 until size) {
            val task = taskAt(i) ?: continue
            task.slot = kept
            all[kept++] = task
            task.kind.members++
            if (task.serial) serialTasks.add(task) else task.kind.count++
        }
        all.fill(null, kept, size)
        size = kept
        if (all.size > INITIAL_CAPACITY && size < all.size / 4) all = all.copyOf(maxOf(INITIAL_CAPACITY, all.size / 2))
        kinds.values.removeIf { it.members == 0 }
        ranked.clear()
        for (kind in kinds.values) if (kind.count > 0) ranked.add(kind)
        rank()
        waveSize = size - serialTasks.size
        if (order.size < waveSize || order.size > INITIAL_CAPACITY && waveSize < order.size / 4) {
            order = arrayOfNulls(maxOf(INITIAL_CAPACITY, waveSize + waveSize / 2))
        } else {
            order.fill(null, waveSize, order.size)
        }
        // A counting sort: each kind's segment fills in member order.
        val next = IntArray(ranked.size) { ranked[it].start }
        for (i in 0 until size) {
            val task = taskAt(i)!!
            if (!task.serial) order[next[task.kind.rank]++] = task.waveTask(labels)
        }
        rebuild = false
        planned = false
    }

    /** Sorts [ranked] costliest first (unmeasured kinds first, so they are measured alone) and assigns segments. */
    private fun rank() {
        ranked.sortWith(BY_LONGEST)
        var start = 0
        for ((rank, kind) in ranked.withIndex()) {
            kind.rank = rank
            kind.start = start
            start += kind.count
        }
    }

    /**
     * Assembly: reorders the kinds' segments when one became clearly costlier than one ahead of it (copying segments, not
     * visiting members), then cuts the groups: within each kind, equal runs of members whose expected cost is
     * about the wave's expected cost over [GROUPS_PER_PARTICIPANT] groups per participant. A kind keeps its
     * group size while that stays within [RESIZE_MARGIN] of the ideal, so a steady wave keeps its groups and the
     * server thread writes none of them.
     */
    private fun plan(participants: Int) {
        if (outOfOrder()) {
            val previous = IntArray(ranked.size) { ranked[it].start }
            val kindsBefore = ranked.toTypedArray()
            rank()
            if (spare.size < order.size) spare = arrayOfNulls(order.size)
            for (r in kindsBefore.indices) {
                val kind = kindsBefore[r]
                order.copyInto(spare, kind.start, previous[r], previous[r] + kind.count)
            }
            val swap = order
            order = spare
            spare = swap
            spare.fill(null)
            planned = false
        }
        if (waveSize == 0) {
            wave.clear()
            return
        }
        val fallback = fallbackNanos()
        var expected = 0.0
        for (kind in ranked) expected += kind.estimate(fallback) * kind.count
        val slots = maxOf(1, participants)
        val target = expected / (slots * GROUPS_PER_PARTICIPANT)
        if (sizes.size < ranked.size) sizes = IntArray(ranked.size * 2)
        var groups = 0
        for ((r, kind) in ranked.withIndex()) {
            val ideal = target / kind.estimate(fallback)
            val current = kind.perGroup
            // Rounded down: a member that costs more than half a group's share runs alone.
            val keep = current in 1..kind.count && current >= ideal / RESIZE_MARGIN && current <= ideal * RESIZE_MARGIN
            sizes[r] = if (keep) current else ideal.toInt().coerceIn(1, kind.count)
            groups += (kind.count + sizes[r] - 1) / sizes[r]
        }
        if (groups > slots * MAX_GROUPS_PER_PARTICIPANT) {
            // Rounding down made too many: scale every kind's groups up so the pool still claims them singly.
            val scale = groups.toDouble() / (slots * GROUPS_PER_PARTICIPANT)
            for (r in ranked.indices) sizes[r] = ceil(sizes[r] * scale).toInt().coerceIn(1, ranked[r].count)
        }
        var same = planned && participants == plannedParticipants
        for ((r, kind) in ranked.withIndex()) {
            if (kind.perGroup == sizes[r]) continue
            kind.perGroup = sizes[r]
            same = false
        }
        if (same) return
        wave.clear()
        used = 0
        val slices = maxOf(1, homeCount)
        // With homes, phase = kind and cell = one home's slice of it: every participant claims a kind out before the
        // next, so kinds still start costliest first.
        if (homeCount > 0) homes.reset(ranked.size, homeCount)
        for (r in ranked.indices) for (h in 0 until slices) {
            if (homeCount > 0) homes.set(r, h, used)
            cutSlice(r, h, slices)
        }
        if (homeCount > 0) homes.set(ranked.size, 0, used)
        if (groupPool.size > used * 2 + INITIAL_GROUPS) groupPool.subList(used * 2, groupPool.size).clear()
        planned = true
        plannedParticipants = participants
    }

    /** Appends the groups of slice [slice] of [slices] of kind rank [r] to [wave]: about equal runs of its members. */
    private fun cutSlice(r: Int, slice: Int, slices: Int) {
        val kind = ranked[r]
        val perGroup = kind.perGroup.coerceIn(1, kind.count)
        val from = kind.start + (kind.count.toLong() * slice / slices).toInt()
        val members = kind.start + (kind.count.toLong() * (slice + 1) / slices).toInt() - from
        if (members == 0) return
        val groups = (members + perGroup - 1) / perGroup
        // Equal sizes: the first (members % groups) groups take one extra member.
        val base = members / groups
        val extra = members % groups
        var start = from
        for (g in 0 until groups) {
            val group = if (used < groupPool.size) groupPool[used] else Group().also(groupPool::add)
            if (groupMembers.size <= used) growGroups(used + 1)
            val size = base + if (g < extra) 1 else 0
            groupMembers[used] = size
            groupKinds[used] = r
            group.index = used++
            group.start = start
            start += size
            group.end = start
            wave.add(group)
        }
    }

    private fun growGroups(needed: Int) {
        val size = maxOf(needed, groupMembers.size + groupMembers.size / 2)
        groupNanos = LongArray(size)
        groupEnds = LongArray(size)
        groupBegins = LongArray(size)
        groupMembers = groupMembers.copyOf(size)
        groupKinds = groupKinds.copyOf(size)
    }

    /** Whether some kind's costliest member is costlier, by more than [REORDER_MARGIN], than one ahead of it. */
    private fun outOfOrder(): Boolean {
        for (r in 1 until ranked.size) {
            val ahead = ranked[r - 1].longestNanos
            val behind = ranked[r].longestNanos
            if (ahead > 0 && (behind == 0.0 || behind > ahead * REORDER_MARGIN)) return true
        }
        return false
    }

    /** Cost assumed for unmeasured kinds: the measured kinds' mean per member, or a default before any. */
    private fun fallbackNanos(): Double {
        var nanos = 0.0
        var tasks = 0
        for (kind in ranked) if (kind.nanosPerTask > 0) {
            nanos += kind.nanosPerTask * kind.count
            tasks += kind.count
        }
        return if (tasks == 0) DEFAULT_NANOS else nanos / tasks
    }

    /**
     * A group's measurement, on the thread that ran it: its time, and for the profile only, when it began and ended
     * and, on the dispatching thread, how far that thread got. The wave's barrier publishes them to [learn].
     */
    private fun timed(index: Int, began: Long, ended: Long) {
        groupNanos[index] = ended - began
        groupBegins[index] = began
        groupEnds[index] = ended
        if (Thread.currentThread() === caller && ended > callerDone) callerDone = ended
    }

    /** Learning, after a completed wave (every group ran): the kinds' costs, then the wave's expected longest group. */
    private fun learn() {
        if (wave.isEmpty()) return
        learnKinds()
        val longest = groupNanos[longestGroup()].toDouble()
        longestNanos = smooth(longestNanos, longest, if (longest > longestNanos) LONGEST_RISE else LONGEST_FALL)
    }

    /**
     * Each kind's mean and costliest-member cost from its groups' times: a group's per-member time is its time over
     * its size, so the costliest member of a kind whose groups hold several is known only as its group's share.
     */
    private fun learnKinds() {
        if (kindNanos.size < ranked.size) {
            kindNanos = LongArray(ranked.size)
            kindCostliest = DoubleArray(ranked.size)
        }
        kindNanos.fill(0L, 0, ranked.size)
        kindCostliest.fill(0.0, 0, ranked.size)
        for (g in wave.indices) {
            val time = groupNanos[g]
            val r = groupKinds[g]
            kindNanos[r] += time
            val perMember = time.toDouble() / groupMembers[g]
            if (perMember > kindCostliest[r]) kindCostliest[r] = perMember
        }
        for ((r, kind) in ranked.withIndex()) {
            val sample = kindNanos[r].toDouble() / kind.count
            kind.nanosPerTask = smooth(kind.nanosPerTask, sample, COST_SMOOTHING)
            kind.longestNanos = smooth(kind.longestNanos, kindCostliest[r], COST_SMOOTHING)
        }
    }

    /** The index of the last wave's longest group, the first of equals; the wave must have groups. */
    private fun longestGroup(): Int {
        var longest = 0
        for (g in 1 until wave.size) if (groupNanos[g] > groupNanos[longest]) longest = g
        return longest
    }

    private fun smooth(previous: Double, sample: Double, weight: Double): Double =
        if (previous == 0.0) sample else previous + (sample - previous) * weight

    /**
     * Profile-only accounting of the last wave: its groups, the longest one and when it started after the wave did,
     * and how many were still running when the dispatching thread finished its last group (the start of its tail
     * wait). Reads the measurement and changes nothing. Server thread, after dispatch.
     */
    internal fun recordLastWave(profile: TickProfile, stage: Stage) {
        if (wave.isEmpty()) return
        val longest = longestGroup()
        var running = 0
        for (g in wave.indices) if (groupEnds[g] > callerDone) running++
        profile.recordFlatGroups(stage, wave.size, groupNanos[longest], groupBegins[longest] - waveStarted, running)
    }

    @Suppress("UNCHECKED_CAST")
    private fun taskAt(i: Int): PersistentTickTasks<E>.Task? = all[i] as PersistentTickTasks<E>.Task?

    private fun clear() {
        changes.clear()
        reclassifications.clear()
        pending.set(0)
        byId.clear()
        all.fill(null, 0, size)
        size = 0
        serialTasks.clear()
        ranked.clear()
        order.fill(null)
        waveSize = 0
        wave.clear()
        rebuild = true
        rulesKey = UNSET
    }

    /** Members with a task, for checks. Server thread, outside dispatch. */
    val memberCount: Int get() = byId.size

    /** Whether [item] has a task and it is serial (true), in the wave (false) or absent (null). For checks. */
    fun isSerial(item: E): Boolean? {
        val task = byId.get(list.id(item))
        if (task == null || task.item !== item) return null
        return task.serial
    }

    /** The wave's current members (or labelled wrappers) in wave order, for checks. */
    internal val waveTasks: List<Runnable> get() = List(waveSize) { order[it]!! }

    /** The wave's current groups, for checks. */
    internal val waveGroups: List<Runnable> get() = wave

    /** The learned costs of the members of kind [key], or null without members; for checks. */
    internal fun learnedKind(key: Any): Kind? = kinds[key]

    private companion object {
        val UNSET = Any()
        const val INITIAL_CAPACITY = 256
        const val INITIAL_GROUPS = 64
        const val MAX_OWNER_DEPTH = 64

        /**
         * Expected-cost share of a group: the wave's expected cost over this many groups per participant. With sizes
         * rounded down, 10 balanced a replayed 600-mob fight as well as claiming every member alone, while a wave of
         * thousands of cheap members needs only about 10 claims per participant.
         */
        const val GROUPS_PER_PARTICIPANT = 10

        /**
         * At most this many groups per participant: `WorkerPool` claims one task at a time only while a wave has
         * fewer than 16 per participant; beyond that it would claim several groups at once.
         */
        const val MAX_GROUPS_PER_PARTICIPANT = 15

        /** How much costlier a kind must become than one ahead of it before members are reordered. */
        const val REORDER_MARGIN = 1.25

        /** How far a kind's group size may drift from the ideal before its groups are cut again. */
        const val RESIZE_MARGIN = 1.25

        const val COST_SMOOTHING = 0.1

        /** Smoothing of [longestNanos]: slow up, fast down, so an overestimate (which costs threads) is brief. */
        const val LONGEST_RISE = 0.1
        const val LONGEST_FALL = 0.5

        /** Assumed cost per member before any kind was measured. */
        const val DEFAULT_NANOS = 50_000.0

        /**
         * Unmeasured kinds first, then by costliest member, costliest first; ties keep their previous order (the
         * sort is stable).
         */
        val BY_LONGEST = Comparator<Kind> { a, b ->
            when {
                a.longestNanos == 0.0 || b.longestNanos == 0.0 -> (a.longestNanos != 0.0).compareTo(b.longestNanos != 0.0)
                else -> b.longestNanos.compareTo(a.longestNanos)
            }
        }

        /** Queued reports beyond which a full rescan is cheaper, and an idle list never grows without bound. */
        const val MAX_PENDING = 1 shl 16
    }
}
