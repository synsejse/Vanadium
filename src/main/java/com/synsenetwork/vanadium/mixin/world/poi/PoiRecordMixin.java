package com.synsenetwork.vanadium.mixin.world.poi;

import com.synsenetwork.vanadium.concurrent.PoiTicketAccess;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A record's free tickets are its only mutable state. They change by compare-and-set and are read as volatile, so
 * a record takes no monitor and is a leaf of the POI lock hierarchy (docs/ARCHITECTURE.md, "POI locking"): it is
 * read under a section's monitor and, for village centres, under the distance graph's. A ticket change runs the
 * section's dirty callback after the swap, holding nothing; that callback reaches the graph. Records were
 * synchronized through {@code SyncAllMixin}, which made a ticket change (record, then graph and section) and a
 * section read (section, then record) take two monitors in opposite orders.
 */
@Mixin(PoiRecord.class)
public abstract class PoiRecordMixin implements PoiTicketAccess {
    @Unique
    private static final VarHandle vanadium$FREE_TICKETS = vanadium$freeTickets();

    @Shadow
    @Final
    private Holder<PoiType> poiType;

    @Shadow
    @Final
    private Runnable setDirty;

    @Unique
    private static VarHandle vanadium$freeTickets() {
        try {
            return MethodHandles.lookup().findVarHandle(PoiRecord.class, "freeTickets", int.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PoiRecord.freeTickets not found", e);
        }
    }

    /**
     * @author Vanadium
     * @reason Two claims of the last ticket must not both succeed: vanilla's check-then-decrement relied on the
     * record's monitor, which is gone. Public: Lithium's access widener opens it. Vanilla's and Lithium's claims go
     * through {@code PoiClaims} instead, which also checks that the record is still installed; this stays for
     * other callers.
     */
    @Overwrite
    public boolean acquireTicket() {
        if (!this.vanadium$takeTicket()) {
            return false;
        }
        this.setDirty.run();
        return true;
    }

    /**
     * @author Vanadium
     * @reason As {@link #acquireTicket}: a release never takes the count above the type's maximum.
     */
    @Overwrite
    protected boolean releaseTicket() {
        PoiRecord self = (PoiRecord) (Object) this;
        int max = this.poiType.value().maxTickets();
        int free;
        do {
            free = (int) vanadium$FREE_TICKETS.getVolatile(self);
            if (free >= max) {
                return false;
            }
        } while (!vanadium$FREE_TICKETS.compareAndSet(self, free, free + 1));
        this.setDirty.run();
        return true;
    }

    /** Occupancy tests, the debug count and saving read the latest swap. */
    @Redirect(method = {"pack", "getFreeTickets", "hasSpace", "isOccupied"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;freeTickets:I", opcode = Opcodes.GETFIELD))
    private int vanadium$readFreeTickets(PoiRecord record) {
        return (int) vanadium$FREE_TICKETS.getVolatile(record);
    }

    @Override
    public boolean vanadium$acquireTicket() {
        return this.acquireTicket();
    }

    @Override
    public boolean vanadium$takeTicket() {
        PoiRecord self = (PoiRecord) (Object) this;
        int free;
        do {
            free = (int) vanadium$FREE_TICKETS.getVolatile(self);
            if (free <= 0) {
                return false;
            }
        } while (!vanadium$FREE_TICKETS.compareAndSet(self, free, free - 1));
        return true;
    }

    @Override
    public void vanadium$markDirty() {
        this.setDirty.run();
    }
}
