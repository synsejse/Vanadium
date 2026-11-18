package com.synsenetwork.vanadium.concurrent

import net.minecraft.core.SectionPos
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiRecord

/**
 * The commit step of vanilla's `take` and Lithium's `takeAt` (docs/ARCHITECTURE.md, "POI locking"). Both find a
 * candidate record first, outside any lock (Lithium's searches even collect them into a list), so by the time they
 * claim, the record may have been removed by a block change, dropped by a section refresh, or detached by a column
 * unload; vanilla then took a ticket on a record nothing would save or find again.
 *
 * Under the section's monitor a claim checks that the storage map (read without loading) still holds that section
 * and that the section still holds that record at its position, then takes a ticket by compare-and-set and runs the
 * dirty callback, which reaches the distance graph: section, then the terminal graph monitor, as a release takes
 * them. Removal, refresh and saving (`pack`) are synchronized section methods, so each is ordered entirely before or
 * after a claim. Nothing else runs under the monitor: no caller predicate, no load.
 */
object PoiClaims {
    /** Takes a ticket of [record] if it is still installed in [manager] and has one free; false otherwise. */
    @JvmStatic
    fun claim(manager: PoiManager, record: PoiRecord): Boolean {
        val pos = record.pos
        val sectionPos = SectionPos.asLong(pos)
        val storage = manager as SectionStorageAccess
        val section = storage.`vanadium$installed`(sectionPos) ?: return false
        synchronized(section) {
            if (storage.`vanadium$installed`(sectionPos) !== section) return false
            if ((section as PoiSectionAccess).`vanadium$recordAt`(pos) !== record) return false
            val ticket = record as PoiTicketAccess
            if (!ticket.`vanadium$takeTicket`()) return false
            ticket.`vanadium$markDirty`()
        }
        return true
    }
}
