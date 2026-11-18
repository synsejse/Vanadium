package com.synsenetwork.vanadium.concurrent;

/** Claims one of a POI record's tickets; implemented by {@code PoiRecordMixin}. */
public interface PoiTicketAccess {
    /** Takes a ticket if one is free, as {@code PoiRecord.acquireTicket}; false if none was left. */
    boolean vanadium$acquireTicket();

    /** Takes a ticket if one is free, without the dirty callback; {@code PoiClaims} runs that itself. */
    boolean vanadium$takeTicket();

    /** Runs the record's dirty callback (its section's {@code setDirty}), as a ticket change does. */
    void vanadium$markDirty();
}
