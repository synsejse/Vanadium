package com.synsenetwork.vanadium.concurrent;

/** Claims one of a POI record's tickets; implemented by {@code PoiRecordMixin}. */
public interface PoiTicketAccess {
    /** Takes a ticket if one is free, as {@code PoiRecord.acquireTicket}; false if none was left. */
    boolean vanadium$acquireTicket();
}
