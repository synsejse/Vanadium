package com.synsenetwork.vanadium.tick

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PersistentTickTasksTest {
    private class Item(val id: Int) {
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
            override fun tick(item: Item, body: Consumer<Item>) = body.accept(item)
        }
        val tasks = PersistentTickTasks(rules)
        var rulesKey: Any = Any()

        fun add(item: Item) {
            members.add(item)
            tasks.changed(item)
        }

        fun remove(item: Item) {
            members.remove(item)
            tasks.changed(item)
        }

        /** One tick: serial items in order, then the wave run in list order on this thread. */
        fun tick(labels: Boolean = false, during: (Item) -> Unit = {}): Pair<List<Int>, List<Int>> {
            val serial = ArrayList<Int>()
            val wave = ArrayList<Int>()
            var inWave = false
            tasks.dispatch(rulesKey, labels, { item -> (if (inWave) wave else serial).add(item.id); during(item) },
                { visit -> members.sortedBy { it.id }.forEach(visit::accept) }) { list ->
                inWave = true
                for (task in list) task.run()
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
        val (_, wave) = f.tick { item -> if (item.id == 0) { f.add(items[3]); f.remove(items[1]) } }
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
        val wave = f.tasks.waveTasks
        val first = wave.toList()
        repeat(5) { assertEquals(1000, f.tick().second.size) }
        assertEquals(fixed, f.fixedChecks)
        assertEquals(changing, f.changingChecks)
        assertSame(wave, f.tasks.waveTasks)
        assertEquals(first, f.tasks.waveTasks, "tasks were recreated")
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
            // Each job adds one new item and removes one old one, as spawns and deaths do from workers.
            f.tasks.dispatch(f.rulesKey, false, { }, { }) { list ->
                pool.runWave(List(list.size) { i ->
                    Runnable {
                        list[i].run()
                        f.add(items[1000 + i])
                        if (i % 2 == 0) f.remove(items[i])
                    }
                })
            }
        }
        val (_, wave) = f.tick()
        val expected = (0 until 2000).filter { it >= 1000 || it % 2 == 1 }
        assertEquals(expected, wave.sorted())
        assertEquals(expected.size, f.tasks.memberCount)
    }
}
