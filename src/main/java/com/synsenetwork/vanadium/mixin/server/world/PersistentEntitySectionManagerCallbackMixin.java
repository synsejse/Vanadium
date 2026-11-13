package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.StripedLocks;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PersistentEntitySectionManager.Callback.class)
public abstract class PersistentEntitySectionManagerCallbackMixin<T extends EntityAccess> {
    @Shadow
    private EntitySection<T> currentSection;

    @Shadow
    private long currentSectionKey;

    @Shadow
    @Final
    private T entity;

    /**
     * Section moves are a multi-step transition (remove from old section, create-or-get new section,
     * add, load-status cascade that may delete an emptied section) whose atomicity the concurrent
     * backing maps alone don't give — an add can race a same-section removal and land in an orphaned
     * section. Striping by section key keeps that atomicity while letting entities crossing unrelated
     * sections proceed in parallel.
     */
    @Unique
    private static final StripedLocks SECTION_LOCKS = new StripedLocks(1024);

    @Redirect(method = "onMove", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntitySection;remove(Lnet/minecraft/world/level/entity/EntityAccess;)Z"))
    private boolean updateEntityPosition(EntitySection<T> instance, T entity) {
        this.currentSection.remove(entity);
        return true;
    }

    @Redirect(method = "onRemove", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntitySection;remove(Lnet/minecraft/world/level/entity/EntityAccess;)Z"))
    private boolean remove(EntitySection<T> instance, T entity) {
        this.currentSection.remove(entity);
        return true;
    }

    /**
     * Vanilla's {@code onMove} changes nothing unless the section key changed, so a move within the section
     * (nearly every position write) runs without the stripes. {@code original} still runs: Lithium notifies
     * its movement trackers at its RETURN, under their own lock ({@code EntitySectionMovementTrackingMixin}).
     * <p>
     * The key and section are written only here, by whoever writes the entity's position: its own tick, a
     * foreign write inside an {@code Interactions} scope that owns it, or the server thread outside waves.
     * Those are serialized by entity ownership and wave barriers, so the read below is the entity's own last
     * value and agrees with the check {@code original} makes. A crossing rechecks the key under the stripes,
     * so a write that ran unprotected (an ownership timeout) cannot move the entity out of a section whose
     * stripe it does not hold.
     */
    @WrapMethod(method = "onMove")
    private void updateEntityPosition(Operation<Void> original) {
        while (true) {
            long currentSectionPos = this.currentSectionKey;
            long newSectionPos = SectionPos.asLong(this.entity.blockPosition());
            if (newSectionPos == currentSectionPos) {
                original.call();
                return;
            }
            SECTION_LOCKS.lock(currentSectionPos, newSectionPos);
            try {
                if (this.currentSectionKey == currentSectionPos) {
                    original.call();
                    return;
                }
            } finally {
                SECTION_LOCKS.unlock(currentSectionPos, newSectionPos);
            }
        }
    }

    @WrapMethod(method = "onRemove")
    private void remove(Entity.RemovalReason reason, Operation<Void> original) {
        SECTION_LOCKS.runLocked(this.currentSectionKey, () -> original.call(reason));
    }
}
