package com.synsenetwork.vanadium.tick

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.LongSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PersistentTickTasksTest {
    private class Item(val id: Int, val kind: String = "a", var cost: Long = 1_000) {
        var fixed = false
        var changing = false
        var owner: Item? = null
        val passengers = ArrayList<Item>()
    }

    /** A tick list whose membership is a concurrent set, like vanilla's map after Vanadium's replacement. */
    private class Fixture {
        val members: MutableSet<Item> = ConcurrentHashMap.newKeySet()
        var fixedChecks = 0
        var changingChecks = 0
        val rules = object : PersistentTickTasks.Rules<Item> {
            override fun id(item: Item) = item.id
            override fun isMember(item: Item) = item in members
            override fun fixedSerial(item: Item): Boolean {
                fixedChecks++
                return item.fixed
            }
            override fun changingSerial(item: Item): Boolean {
                changingChecks++
                return item.changing || item.passengers.any { it.fixed || it.changing }
            }
            override fun owner(item: Item) = item.owner
            override fun label(item: Item) = "item${item.id}"
            override fun kind(item: Item) = item.kind
            override fun tick(item: Item, body: Consumer<Item>) = body.accept(item)
        }

        /** Advanced by each member's [Item.cost] as it ticks, so group times are exact. */
        val clock = AtomicLong()
        val tasks = PersistentTickTasks(rules, LongSupplier(clock::get))
        var rulesKey: Any = Any()

        fun add(item: Item) {
            members.add(item)
            tasks.changed(item)
        }

        fun remove(item: Item) {
            members.remove(item)
            tasks.changed(item)
        }

        /** Each wave's groups, as the member ids each ran; filled by [tick]. */
        val groups = ArrayList<List<Int>>()

        /** One tick: serial items in order, then the wave's groups run in list order on this thread. */
        fun tick(labels: Boolean = false, participants: Int = 4, during: (Item) -> Unit = {}): Pair<List<Int>, List<Int>> {
            val serial = ArrayList<Int>()
            val wave = ArrayList<Int>()
            var inWave = false
            groups.clear()
            tasks.dispatch(rulesKey, labels, participants, { item ->
                (if (inWave) wave else serial).add(item.id)
                clock.addAndGet(item.cost)
                during(item)
            }, { visit -> members.sortedBy { it.id }.forEach(visit::accept) }) { list, units ->
                inWave = true
                for (group in list) {
                    val before = wave.size
                    group.run()
                    groups.add(wave.subList(before, wave.size).toList())
                }
                assertEquals(wave.size, units)
            }
            return serial to wave
        }
    }

    @Test
    fun addsDuringATickStartNextTickAndRemovalsStop() {
        val f = Fixture()
        val items = List(6) { Item(it) }
        items.take(3).forEach(f::add)
        assertEquals(emptyList<Int>() to listOf(0, 1, 2), f.tick())
        // Added and removed while the wave runs: the running tick keeps its snapshot.
        val (_, wave) = f.tick(during = { item -> if (item.id == 0) { f.add(items[3]); f.remove(items[1]) } })
        assertEquals(listOf(0, 1, 2), wave)
        assertEquals(emptyList<Int>() to listOf(0, 2, 3), f.tick())
        assertEquals(3, f.tasks.memberCount)
        assertNull(f.tasks.isSerial(items[1]))
        // Removed then re-added before the next dispatch: the task stays, in place.
        f.remove(items[0])
        f.add(items[0])
        f.add(items[0])
        assertEquals(emptyList<Int>() to listOf(0, 2, 3), f.tick())
        f.remove(items[2])
        f.add(items[4])
        f.add(items[5])
        assertEquals(emptyList<Int>() to listOf(0, 3, 4, 5), f.tick())
    }

    @Test
    fun anUnchangedListIsNotVisitedAgain() {
        val f = Fixture()
        repeat(1000) { f.add(Item(it)) }
        f.tick()
        val fixed = f.fixedChecks
        val changing = f.changingChecks
        val first = f.tasks.waveTasks.toList()
        val groups = f.tasks.waveGroups.toList()
        repeat(5) { assertEquals(1000, f.tick().second.size) }
        assertEquals(fixed, f.fixedChecks)
        assertEquals(changing, f.changingChecks)
        assertEquals(first, f.tasks.waveTasks, "tasks were recreated")
        assertEquals(groups, f.tasks.waveGroups, "groups were recreated")
    }

    @Test
    fun serialRulesFollowRulesKeyReportsAndPassengers() {
        val f = Fixture()
        val items = List(5) { Item(it) }
        items.forEach(f::add)
        items[3].fixed = true
        assertEquals(listOf(3) to listOf(0, 1, 2, 4), f.tick())

        // A fixed rule change without a new key is not noticed; with one, everything is reclassified.
        items[3].fixed = false
        items[1].fixed = true
        assertEquals(listOf(3) to listOf(0, 1, 2, 4), f.tick())
        f.rulesKey = Any()
        assertEquals(listOf(1) to listOf(0, 2, 3, 4), f.tick())

        // Portal contact, reported: serial from the next dispatch until it no longer holds.
        items[2].changing = true
        f.tasks.reclassify(items[2])
        assertEquals(listOf(1, 2) to listOf(0, 3, 4), f.tick())
        items[2].changing = false
        assertEquals(listOf(1) to listOf(0, 2, 3, 4), f.tick())

        // A serial passenger mounting a vehicle that itself rides another: the whole chain is reclassified.
        val (root, vehicle, passenger) = Triple(items[0], items[4], items[1])
        vehicle.owner = root
        root.passengers.add(vehicle)
        passenger.owner = vehicle
        vehicle.passengers.add(passenger)
        root.changing = true // The fixture's rule only looks one level down; the real one recurses.
        f.tasks.reclassify(vehicle)
        assertEquals(listOf(0, 1, 4) to listOf(2, 3), f.tick())
        assertEquals(true, f.tasks.isSerial(root))
        // Dismounting releases both without a report.
        vehicle.passengers.clear()
        passenger.owner = null
        root.changing = false
        root.passengers.clear()
        vehicle.owner = null
        assertEquals(listOf(1) to listOf(0, 2, 3, 4), f.tick())
    }

    @Test
    fun labelsWrapTasksOnlyWhileEnabled() {
        val f = Fixture()
        repeat(3) { f.add(Item(it)) }
        f.tick()
        assertTrue(f.tasks.waveTasks.none { it is WaveDiagnostics.NamedTask })
        assertEquals(emptyList<Int>() to listOf(0, 1, 2), f.tick(labels = true))
        val named = f.tasks.waveTasks.map { (it as WaveDiagnostics.NamedTask).label }
        assertEquals(listOf("item0", "item1", "item2"), named)
        f.tick(labels = false)
        assertTrue(f.tasks.waveTasks.none { it is WaveDiagnostics.NamedTask })
    }

    @Test
    fun stopDropsStateAndTheNextDispatchRescans() {
        val f = Fixture()
        val items = List(4) { Item(it) }
        items.take(2).forEach(f::add)
        f.tick()
        f.tasks.stop()
        assertEquals(0, f.tasks.memberCount)
        // Disabled: changes are not queued, and the rescan finds the list as it is.
        f.members.remove(items[0])
        f.add(items[2])
        f.members.add(items[3])
        assertEquals(emptyList<Int>() to listOf(1, 2, 3), f.tick())
    }

    /** A respawned player reuses the old one's id: either report order leaves only the new object. */
    @Test
    fun aReusedIdKeepsOnlyTheCurrentMember() {
        for (newFirst in listOf(false, true)) {
            val f = Fixture()
            val old = Item(1)
            f.add(old)
            f.add(Item(2))
            f.tick()
            val respawned = Item(1)
            f.members.remove(old)
            f.members.add(respawned)
            if (newFirst) { f.tasks.changed(respawned); f.tasks.changed(old) } else { f.tasks.changed(old); f.tasks.changed(respawned) }
            f.tick()
            assertEquals(false, f.tasks.isSerial(respawned))
            assertNull(f.tasks.isSerial(old))
            assertEquals(2, f.tasks.memberCount)
        }
    }

    @Test
    fun membersAreNotTrackedBeforeTheFirstDispatch() {
        val f = Fixture()
        val item = Item(7)
        f.add(item)
        f.remove(item)
        f.add(item)
        assertEquals(emptyList<Int>() to listOf(7), f.tick())
        assertEquals(1, f.tasks.memberCount)
    }

    @Test
    fun concurrentChangesDuringAWaveAreAllApplied() {
        val f = Fixture()
        val items = List(4000) { Item(it) }
        items.take(1000).forEach(f::add)
        f.tick()
        WorkerPool(4).use { pool ->
            f.tasks.dispatch(f.rulesKey, false, 5, { item ->
                // Each member adds one new item and removes every other old one, as spawns and deaths do from workers.
                f.add(items[1000 + item.id])
                if (item.id % 2 == 0) f.remove(item)
            }, { }) { list, _ -> pool.runWave(list) }
        }
        val (_, wave) = f.tick()
        val expected = (0 until 2000).filter { it >= 1000 || it % 2 == 1 }
        assertEquals(expected, wave.sorted())
        assertEquals(expected.size, f.tasks.memberCount)
    }

    /** Ids of a fixture's items in the order a wave ran them, grouped by kind. */
    private fun kindsInOrder(items: List<Item>, wave: List<Int>): List<String> =
        wave.map { id -> items.first { it.id == id }.kind }.distinct()

    @Test
    fun costlierKindsRunFirstEachInMemberOrder() {
        val f = Fixture()
        // Interleaved by kind in member order; "slow" members cost ten times "fast" ones.
        val items = List(40) { i -> if (i % 4 == 0) Item(i, "slow", 10_000) else Item(i, "fast", 1_000) }
        items.forEach(f::add)
        f.tick() // Both kinds unmeasured: member order, then both measured.
        assertEquals(items.map { it.id }, f.tick().second.sorted())
        // Rebuilt (a membership change) with the estimates: slow first.
        val extra = Item(40, "fast", 1_000)
        f.add(extra)
        val (_, wave) = f.tick()
        val all = items + extra
        assertEquals(listOf("slow", "fast"), kindsInOrder(all, wave))
        assertEquals(all.filter { it.kind == "slow" }.map { it.id }, wave.take(10), "slow members keep member order")
        assertEquals(all.filter { it.kind == "fast" }.map { it.id }, wave.drop(10), "fast members keep member order")
        assertEquals(all.size, wave.toSet().size)
    }

    @Test
    fun aKindThatBecomesCostlierMovesAheadWithoutVisitingMembers() {
        val f = Fixture()
        val first = List(20) { Item(it, "first", 2_000) }
        val second = List(20) { Item(20 + it, "second", 1_000) }
        (first + second).forEach(f::add)
        repeat(2) { f.tick() }
        assertEquals(listOf("first", "second"), kindsInOrder(first + second, f.tick().second))
        // Within the margin: no reorder.
        second.forEach { it.cost = 2_200 }
        repeat(30) { f.tick() }
        assertEquals(listOf("first", "second"), kindsInOrder(first + second, f.tick().second))
        // Clearly costlier: reordered from the estimates alone, with no membership change or rule check.
        second.forEach { it.cost = 6_000 }
        val fixed = f.fixedChecks
        val changing = f.changingChecks
        var wave = f.tick().second
        repeat(30) { if (kindsInOrder(first + second, wave).first() != "second") wave = f.tick().second }
        assertEquals(listOf("second", "first"), kindsInOrder(first + second, wave))
        assertEquals(second.map { it.id } + first.map { it.id }, wave)
        assertEquals(fixed, f.fixedChecks)
        assertEquals(changing, f.changingChecks)
    }

    @Test
    fun groupsPartitionTheWaveAndCostlyMembersRunAlone() {
        val f = Fixture()
        val items = List(1000) { i -> if (i < 40) Item(i, "heavy", 400_000) else Item(i, "light", 2_000) }
        items.forEach(f::add)
        repeat(3) { f.tick(participants = 25) }
        val (_, wave) = f.tick(participants = 25)
        assertEquals(items.map { it.id }.toSet(), wave.toSet())
        assertEquals(items.size, wave.size, "a member ran twice")
        assertEquals(wave, f.groups.flatten())
        for (group in f.groups) {
            assertTrue(group.isNotEmpty())
            assertEquals(1, group.map { id -> items[id].kind }.distinct().size, "a group mixes kinds")
        }
        // Heavy members (each costlier than a group's share of the wave) run alone and first.
        assertEquals(items.take(40).map { listOf(it.id) }, f.groups.take(40))
        // Light ones in about equal groups, few enough that the pool claims them singly (< 16 per participant).
        val light = f.groups.drop(40).map { it.size }
        assertTrue(light.max() - light.min() <= 1, "unequal light groups: $light")
        assertTrue(light.max() > 1)
        assertTrue(f.groups.size < 16 * 25, "${f.groups.size} groups")
        // An unchanged list keeps its groups from tick to tick.
        val groups = f.groups.toList()
        f.tick(participants = 25)
        assertEquals(groups, f.groups)
        // Fewer participants: larger groups.
        f.tick(participants = 2)
        assertTrue(f.groups.size < groups.size)
        assertEquals(items.size, f.groups.sumOf { it.size })
    }

    @Test
    fun aFailingMemberDoesNotStopTheRestOfItsGroup() {
        val f = Fixture()
        val items = List(30) { Item(it, "a", 1_000) }
        items.forEach(f::add)
        f.tick(participants = 1) // One participant: a few large groups.
        val group = f.tasks.waveGroups.first()
        val ticked = ArrayList<Int>()
        val failure = assertFailsWith<IllegalStateException> {
            f.tasks.dispatch(f.rulesKey, false, 1, { item ->
                ticked.add(item.id)
                check(item.id != 0 && item.id != 1) { "member ${item.id}" }
            }, { }) { list, _ -> list.first().run() }
        }
        assertEquals("member 0", failure.message)
        assertSame(group, f.tasks.waveGroups.first())
        assertEquals(f.groups.first(), ticked)
        assertTrue(ticked.size > 2)
    }

    @Test
    fun theLastWaveShapeIsProfiled() {
        val f = Fixture()
        val items = List(12) { i -> if (i == 3) Item(i, "slow", 50_000) else Item(i, "fast", 1_000) }
        items.forEach(f::add)
        repeat(3) { f.tick(participants = 3) }
        val profile = TickProfile()
        f.tick(participants = 3)
        f.tasks.recordLastWave(profile, Stage.ENTITY)
        val entity = profile.toJson().getAsJsonObject("stages").getAsJsonObject("ENTITY")
        assertEquals(f.groups.size, entity.get("groups").asInt)
        assertEquals(0.05, entity.getAsJsonObject("longestGroup").get("p99Ms").asDouble, 1e-9)
        // The fixture runs every group on the dispatching thread, so none is left when it finishes.
        assertEquals(0.0, entity.get("groupsRunningAtTailStart").asDouble)
    }

    @Test
    fun groupSizesHoldThroughSmallCostChanges() {
        val f = Fixture()
        val heavy = List(50) { Item(it, "heavy", 20_000) }
        val light = List(500) { Item(50 + it, "light", 1_000) }
        (heavy + light).forEach(f::add)
        repeat(40) { f.tick(participants = 8) }
        val sizes = f.groups.map { it.size }
        // Light members 15% costlier: within the margin, the cut stays.
        light.forEach { it.cost = 1_150 }
        repeat(40) { f.tick(participants = 8) }
        assertEquals(sizes, f.groups.map { it.size })
        // Three times costlier: each is a larger share of the wave, so light groups shrink.
        light.forEach { it.cost = 3_000 }
        repeat(40) { f.tick(participants = 8) }
        val now = f.groups.map { it.size }
        assertTrue(now.drop(now.size - 10).max() < sizes.drop(sizes.size - 10).max(), "$sizes -> $now")
        assertEquals(550, now.sum())
    }

    @Test
    fun roundingDownNeverMakesTooManyGroupsToClaimSingly() {
        val f = Fixture()
        // 38 equal members over 2 participants: each is 1.9 members' share of a group, which rounds down to 1.
        val items = List(38) { Item(it, "a", 1_000) }
        items.forEach(f::add)
        repeat(3) { f.tick(participants = 2) }
        assertTrue(f.groups.size <= 15 * 2, "${f.groups.size} groups")
        assertEquals(items.map { it.id }, f.groups.flatten())
        val groups = f.groups.toList()
        f.tick(participants = 2)
        assertEquals(groups, f.groups, "the capped cut is not kept")
    }
}
