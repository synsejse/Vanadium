package com.synsenetwork.vanadium.tick

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.LongSupplier

/**
 * One persistent task per member of a tick list, kept across ticks, so that dispatching an unchanged
 * list costs nothing per member. The list's owner reports membership changes ([changed]) and possible
 * serial-rule changes ([reclassify]) from any thread; they take effect at the next [dispatch], which
 * runs on the server thread between waves. That matches vanilla's iteration over a snapshot: a member
 * added during a tick first ticks next tick, and one removed during a tick may still be visited by it
 * (vanilla's body returns early for removed entities).
 *
 * Classification is cached per task: the fixed part ([Rules.fixedSerial], which depends on the member's
 * type and on the rules identified by the `rulesKey` passed to [dispatch]) is recomputed when that key
 * changes; the changing part ([Rules.changingSerial]) is recomputed for members reported through
 * [reclassify], for each member and its owners up the [Rules.owner] chain, and every dispatch for members
 * that are serial only through it, so they return to the wave once it no longer holds.
 *
 * The wave is ordered by expected cost, so that long ticks start early instead of being claimed last and
 * leaving the other participants waiting for them. What predicts a member's cost is its kind ([Rules.kind]:
 * the entity type), not its own recent ticks: a member's long ticks are sporadic, while the share of long
 * ticks per kind is stable. Kinds run costliest first, each in member order (which keeps
 * neighbours together where membership is spatial), and the wave is a fixed list of [Group]s: consecutive
 * members of one kind sized to about the same expected cost, about [GROUPS_PER_PARTICIPANT] per participant,
 * so that the worker pool claims them one at a time and costly members run alone. Each group times itself;
 * that is the only measurement, and it updates its kind's cost after the wave. Groups are cut again, without
 * visiting members, only when a kind's ideal size drifts; members are reordered only when the list is rebuilt
 * or a kind becomes clearly costlier than one ahead of it. Correctness never depends on the order.
 *
 * Not thread-safe apart from [changed] and [reclassify]; [dispatch] and [stop] belong to the server thread.
 */
class PersistentTickTasks<E : Any> internal constructor(private val rules: Rules<E>, private val clock: LongSupplier) {
    constructor(rules: Rules<E>) : this(rules, LongSupplier(System::nanoTime))

    interface Rules<E> {
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
    }

    /** Runs a wave's [groups], which tick [members] members between them, as one flat wave. */
    fun interface WaveRunner {
        fun run(groups: List<Runnable>, members: Int)
    }

    inner class Task internal constructor(@JvmField val item: E, internal val kind: Kind) : Runnable {
        internal var slot = -1
        internal var fixed = false
        internal var serial = false
        private var named: Runnable? = null

        override fun run() = rules.tick(item, body!!)

        internal fun waveTask(labels: Boolean): Runnable =
            if (!labels) this else named ?: WaveDiagnostics.NamedTask(rules.label(item), this).also { named = it }
    }

    /** Members sharing a [Rules.kind]: their place in [order] and their measured cost. Server thread only. */
    internal class Kind(val key: Any) {
        /** Smoothed wall time per member tick; 0 until measured. */
        var nanosPerTask = 0.0
        var start = 0
        var count = 0
        internal var rank = 0
        internal var members = 0

        /** Members per group, and this kind's groups in the wave. */
        internal var perGroup = 0
        internal var firstGroup = 0
        internal var groups = 0

        fun estimate(fallback: Double): Double = if (nanosPerTask > 0) nanosPerTask else fallback
    }

    /**
     * One claim of the wave: members [start] until [end] of [order], all of one kind. Runs every member even
     * when one throws (as the pool does for separate tasks) and rethrows the first failure afterwards. Its
     * time goes to [groupNanos] and its end to [groupEnds], dense arrays the server thread reads after the
     * wave without touching group objects; the groups themselves are only read during a wave.
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
            val ended = clock.asLong
            groupNanos[index] = ended - began
            groupEnds[index] = ended
            if (Thread.currentThread() === caller && ended > callerDone) callerDone = ended
            failure?.let { throw it }
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

    /** The wave's members (or their labelled wrappers), by kind; [waveSize] are used. */
    private var order = arrayOfNulls<Runnable>(INITIAL_CAPACITY)
    private var spare = arrayOfNulls<Runnable>(0)
    private var waveSize = 0
    private val groupPool = ArrayList<Group>()
    private val wave = ArrayList<Runnable>()
    private var sizes = IntArray(16)
    private var groupNanos = LongArray(INITIAL_GROUPS)
    private var groupEnds = LongArray(INITIAL_GROUPS)

    /** Whether [wave]'s groups match [order] and the kinds' sizes; cleared when either changes. */
    private var planned = false
    private var plannedParticipants = 0
    private var caller: Thread? = null
    private var waveStarted = 0L

    /** The end of the last group the dispatching thread ran; written only by that thread. */
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
     * Brings the tasks up to date, runs serial members through [body] on the calling thread in list order,
     * then passes the wave's groups and its member count to [runWave]. The list passed stays unchanged until
     * the next call. [scan] visits every current member; it is used when tracking (re)starts. [participants]
     * is the most threads the wave can have, the caller included; it sizes the groups.
     */
    fun dispatch(rulesKey: Any?, labels: Boolean, participants: Int, body: Consumer<E>, scan: (Consumer<E>) -> Unit,
                 runWave: WaveRunner) {
        update(rulesKey, labels, scan)
        plan(participants)
        this.body = body
        try {
            for (i in serialTasks.indices) body.accept(serialTasks[i].item)
            caller = Thread.currentThread()
            waveStarted = clock.asLong
            callerDone = waveStarted
            runWave.run(wave, waveSize)
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

    internal fun update(rulesKey: Any?, labels: Boolean, scan: (Consumer<E>) -> Unit) {
        if (!tracking) {
            // Tracking first, so a change during the scan is queued and applied after it.
            clear()
            tracking = true
            scan(Consumer { sync(it) })
        }
        drain(changes) { sync(it) }
        if (rulesKey !== this.rulesKey) {
            this.rulesKey = rulesKey
            for (i in 0 until size) {
                val task = taskAt(i) ?: continue
                task.fixed = rules.fixedSerial(task.item)
                classify(task)
            }
        }
        drain(reclassifications) { item ->
            var current: E? = item
            var depth = 0
            while (current != null && depth++ < MAX_OWNER_DEPTH) {
                val task = byId.get(rules.id(current))
                if (task != null && task.item === current) classify(task)
                current = rules.owner(current)
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
        val id = rules.id(item)
        val task = byId.get(id)
        if (rules.isMember(item)) {
            if (task != null && task.item === item) return
            if (task != null) remove(task)
            add(item, id)
        } else if (task != null && task.item === item) {
            remove(task)
        }
    }

    private fun add(item: E, id: Int) {
        val key = rules.kind(item)
        val task = Task(item, kinds.getOrPut(key) { Kind(key) })
        task.fixed = rules.fixedSerial(item)
        task.serial = task.fixed || rules.changingSerial(item)
        if (size == all.size) all = all.copyOf(size * 2)
        task.slot = size
        all[size++] = task
        byId.put(id, task)
        rebuild = true
    }

    private fun remove(task: Task) {
        all[task.slot] = null
        task.slot = -1
        byId.remove(rules.id(task.item))
        rebuild = true
    }

    private fun classify(task: Task) {
        val serial = task.fixed || rules.changingSerial(task.item)
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
        ranked.sortWith(BY_COST)
        var start = 0
        for ((rank, kind) in ranked.withIndex()) {
            kind.rank = rank
            kind.start = start
            start += kind.count
        }
    }

    /**
     * Reorders the kinds' segments when one became clearly costlier than one ahead of it (copying segments, not
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
                System.arraycopy(order, previous[r], spare, kind.start, kind.count)
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
            for (r in ranked.indices) sizes[r] = Math.ceil(sizes[r] * scale).toInt().coerceIn(1, ranked[r].count)
        }
        var same = planned && participants == plannedParticipants
        for ((r, kind) in ranked.withIndex()) {
            if (kind.perGroup == sizes[r]) continue
            kind.perGroup = sizes[r]
            same = false
        }
        if (same) return
        wave.clear()
        var used = 0
        for (kind in ranked) {
            val perGroup = kind.perGroup.coerceIn(1, kind.count)
            val groups = (kind.count + perGroup - 1) / perGroup
            kind.firstGroup = used
            kind.groups = groups
            // Equal sizes: the first (count % groups) groups take one extra member.
            val base = kind.count / groups
            val extra = kind.count % groups
            var start = kind.start
            for (g in 0 until groups) {
                val group = if (used < groupPool.size) groupPool[used] else Group().also(groupPool::add)
                group.index = used++
                group.start = start
                start += base + if (g < extra) 1 else 0
                group.end = start
                wave.add(group)
            }
        }
        if (groupNanos.size < used) {
            groupNanos = LongArray(used + used / 2)
            groupEnds = LongArray(groupNanos.size)
        }
        if (groupPool.size > used * 2 + INITIAL_GROUPS) groupPool.subList(used * 2, groupPool.size).clear()
        planned = true
        plannedParticipants = participants
    }

    /** Whether some kind is costlier, by more than [REORDER_MARGIN], than one ahead of it. */
    private fun outOfOrder(): Boolean {
        for (r in 1 until ranked.size) {
            val ahead = ranked[r - 1].nanosPerTask
            val behind = ranked[r].nanosPerTask
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

    /** After a completed wave (every group ran): each kind's cost from its groups' times. */
    private fun learn() {
        if (wave.isEmpty()) return
        val nanos = groupNanos
        for (kind in ranked) {
            var sum = 0L
            for (g in kind.firstGroup until kind.firstGroup + kind.groups) sum += nanos[g]
            val sample = sum.toDouble() / kind.count
            kind.nanosPerTask = if (kind.nanosPerTask == 0.0) sample else kind.nanosPerTask + (sample - kind.nanosPerTask) * COST_SMOOTHING
        }
    }

    /**
     * The last wave's shape for the profile: its groups, the longest one, and how many were still running when
     * the dispatching thread finished its last group (the start of its tail wait). Server thread, after dispatch.
     */
    internal fun recordLastWave(profile: TickProfile, stage: Stage) {
        if (wave.isEmpty()) return
        var longest = 0L
        var running = 0
        for (g in wave.indices) {
            longest = maxOf(longest, groupNanos[g])
            if (groupEnds[g] > callerDone) running++
        }
        profile.recordFlatGroups(stage, wave.size, longest, running)
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
    fun isSerial(item: E): Boolean? = byId.get(rules.id(item))?.takeIf { it.item === item }?.serial

    /** The wave's current members (or labelled wrappers) in wave order, for checks. */
    internal val waveTasks: List<Runnable> get() = List(waveSize) { order[it]!! }

    /** The wave's current groups, for checks. */
    internal val waveGroups: List<Runnable> get() = wave

    internal val serialItems: List<E> get() = serialTasks.map { it.item }

    private companion object {
        val UNSET = Any()
        const val INITIAL_CAPACITY = 256
        const val INITIAL_GROUPS = 64
        const val MAX_OWNER_DEPTH = 64

        /**
         * Expected-cost share of a group: the wave's expected cost over this many groups per participant. In a
         * replay of a 600-mob fight at 24 and 48 workers, 10 (with sizes rounded down) balanced as well as claiming
         * every member alone, while a wave of thousands of cheap members needs only about 10 claims per participant.
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

        /** Assumed cost per member before any kind was measured. */
        const val DEFAULT_NANOS = 50_000.0

        /** Unmeasured kinds first, then costliest first; ties keep their previous order (the sort is stable). */
        val BY_COST = Comparator<Kind> { a, b ->
            when {
                a.nanosPerTask == 0.0 || b.nanosPerTask == 0.0 -> (a.nanosPerTask != 0.0).compareTo(b.nanosPerTask != 0.0)
                else -> b.nanosPerTask.compareTo(a.nanosPerTask)
            }
        }

        /** Queued reports beyond which a full rescan is cheaper, and an idle list never grows without bound. */
        const val MAX_PENDING = 1 shl 16
    }
}
