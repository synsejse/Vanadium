package com.synsenetwork.vanadium.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.StripedLocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.entity.EntityLike;
import net.minecraft.world.entity.EntityTrackingSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerEntityManager.Listener.class)
public abstract class ServerEntityManagerListenerMixin<T extends EntityLike> implements AutoCloseable {
    @Shadow
    private EntityTrackingSection<T> section;

    @Shadow
    private long sectionPos;

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

    @Redirect(method = "updateEntityPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityTrackingSection;remove(Lnet/minecraft/world/entity/EntityLike;)Z"))
    private boolean updateEntityPosition(EntityTrackingSection<T> instance, T entity) {
        this.section.remove(entity);
        return true;
    }

    @Redirect(method = "remove", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityTrackingSection;remove(Lnet/minecraft/world/entity/EntityLike;)Z"))
    private boolean remove(EntityTrackingSection<T> instance, T entity) {
        this.section.remove(entity);
        return true;
    }

    @WrapMethod(method = "updateEntityPosition")
    private void updateEntityPosition(Operation<Void> original) {
        long newSectionPos = ChunkSectionPos.toLong(this.entity.getBlockPos());
        SECTION_LOCKS.runLocked(this.sectionPos, newSectionPos, original::call);
    }

    @WrapMethod(method = "remove")
    private void remove(Entity.RemovalReason reason, Operation<Void> original) {
        SECTION_LOCKS.runLocked(this.sectionPos, () -> original.call(reason));
    }
}
