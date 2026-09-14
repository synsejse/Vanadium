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
public abstract class ServerEntityManagerListenerMixin<T extends EntityAccess> implements AutoCloseable {
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
     * sections proceed in parallel (previously one global lock serialized every crossing world-wide).
     */
    @Unique
    private static final StripedLocks SECTION_LOCKS = new StripedLocks(64);

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

    @WrapMethod(method = "onMove")
    private void updateEntityPosition(Operation<Void> original) {
        long newSectionPos = SectionPos.asLong(this.entity.blockPosition());
        SECTION_LOCKS.runLocked(this.currentSectionKey, newSectionPos, original::call);
    }

    @WrapMethod(method = "onRemove")
    private void remove(Entity.RemovalReason reason, Operation<Void> original) {
        SECTION_LOCKS.runLocked(this.currentSectionKey, () -> original.call(reason));
    }
}
