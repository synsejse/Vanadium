package com.synsenetwork.vanadium.tick

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

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
 * Not thread-safe apart from [changed] and [reclassify]; [dispatch] and [stop] belong to the server thread.
 */
class PersistentTickTasks<E : Any>(private val rules: Rules<E>) {
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

        /** Runs one member's wave task with this tick's body. */
        fun tick(item: E, body: Consumer<E>)
    }

    inner class Task internal constructor(@JvmField val item: E) : Runnable {
        internal var slot = -1
        internal var fixed = false
        internal var serial = false
        private var named: Runnable? = null

        override fun run() = rules.tick(item, body!!)

        internal fun waveTask(labels: Boolean): Runnable =
            if (!labels) this else named ?: WaveDiagnostics.NamedTask(rules.label(item), this).also { named = it }
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
    private val wave = ArrayList<Runnable>()
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
     * then passes the wave's tasks to [runWave]. The list passed stays unchanged until the next call.
     * [scan] visits every current member; it is used when tracking (re)starts.
     */
    fun dispatch(rulesKey: Any?, labels: Boolean, body: Consumer<E>, scan: (Consumer<E>) -> Unit, runWave: (List<Runnable>) -> Unit) {
        update(rulesKey, labels, scan)
        this.body = body
        try {
            for (i in serialTasks.indices) body.accept(serialTasks[i].item)
            runWave(wave)
        } finally {
            this.body = null
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
        val task = Task(item)
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

    /** Compacts the member array in place and refills the serial and wave lists in member order. */
    private fun rebuildLists() {
        serialTasks.clear()
        wave.clear()
        var kept = 0
        for (i in 0 until size) {
            val task = taskAt(i) ?: continue
            task.slot = kept
            all[kept++] = task
            if (task.serial) serialTasks.add(task) else wave.add(task.waveTask(labels))
        }
        all.fill(null, kept, size)
        size = kept
        if (all.size > INITIAL_CAPACITY && size < all.size / 4) all = all.copyOf(maxOf(INITIAL_CAPACITY, all.size / 2))
        rebuild = false
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
        wave.clear()
        rebuild = true
        rulesKey = UNSET
    }

    /** Members with a task, for checks. Server thread, outside dispatch. */
    val memberCount: Int get() = byId.size

    /** Whether [item] has a task and it is serial (true), in the wave (false) or absent (null). For checks. */
    fun isSerial(item: E): Boolean? = byId.get(rules.id(item))?.takeIf { it.item === item }?.serial

    /** The wave's current task list, for checks. */
    internal val waveTasks: List<Runnable> get() = wave

    internal val serialItems: List<E> get() = serialTasks.map { it.item }

    private companion object {
        val UNSET = Any()
        const val INITIAL_CAPACITY = 256
        const val MAX_OWNER_DEPTH = 64

        /** Queued reports beyond which a full rescan is cheaper, and an idle list never grows without bound. */
        const val MAX_PENDING = 1 shl 16
    }
}
