package com.synsenetwork.vanadium.tick

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TickEventsTest {
    @Test
    fun wavesAndPreparationsAreRecordedWithTheirShape() {
        val file = Files.createTempFile("vanadium-events", ".jfr")
        val pool = WorkerPool(2)
        try {
            Recording().use { recording ->
                listOf("vanadium.Wave", "vanadium.Preparation", "vanadium.ServerTick").forEach { recording.enable(it) }
                recording.start()
                val tick = ServerTickEvent()
                tick.begin()
                val scheduler = TickScheduler(pool, 2)
                scheduler.setDimension("test:world")
                scheduler.begin(Stage.BLOCK_ENTITY)
                for (x in intArrayOf(0, 0, 4, 8)) scheduler.enqueue(Stage.BLOCK_ENTITY, x, 0, {}, null)
                scheduler.run(Stage.BLOCK_ENTITY)
                scheduler.prepare(Preparation.TRACKING_DIFFS, List(5) { Runnable {} })
                tick.commit()
                recording.stop()
                recording.dump(file)
            }
            val events = RecordingFile.readAllEvents(file).groupBy { it.eventType.name }
            val wave = events.getValue("vanadium.Wave").single()
            assertEquals("BLOCK_ENTITY", wave.getString("stage"))
            assertEquals("test:world", wave.getString("dimension"))
            assertEquals(3, wave.getInt("regions"))
            assertEquals(4, wave.getInt("tasks"))
            assertEquals(3, wave.getInt("participants")) // Two workers plus the calling thread.
            assertEquals(2, wave.getInt("isolation"))
            val preparation = events.getValue("vanadium.Preparation").single()
            assertEquals("TRACKING_DIFFS", preparation.getString("kind"))
            assertEquals(5, preparation.getInt("jobs"))
            assertTrue(events.getValue("vanadium.ServerTick").single().duration >= wave.duration)
        } finally {
            pool.close()
            Files.deleteIfExists(file)
        }
    }
}
