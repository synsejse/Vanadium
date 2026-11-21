package com.synsenetwork.vanadium.mixin.world.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.entity.InsideBlockFastPath;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the per-tick allocations in {@code Entity.checkInsideBlocks} ({@code LongOpenHashSet},
 * {@code BlockPos.betweenCornersInDirection} iterator/lambda, {@code AABB.deflate}/{@code move}, {@code Vec3.relative},
 * {@code AtomicInteger} and the 10-capture {@code BlockStepVisitor}) with an exact primitive traversal and direct
 * chunk-section air skipping. Falls back to vanilla whenever entity-block-intersection debug subscribers are active.
 */
@Mixin(Entity.class)
public abstract class EntityInsideBlocksMixin {
    @Unique
    private static final ClassValue<Boolean> VANADIUM$DEFAULT_MAKE_BB = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            Class<?> current = type;
            while (current != null && current != Entity.class) {
                try {
                    current.getDeclaredMethod("makeBoundingBox", Vec3.class);
                    return false;
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }
            return true;
        }
    };

    @Shadow
    private LongSet visitedBlocks;

    @Shadow
    private Level level;

    @Shadow
    protected abstract boolean isAffectedByBlocks();

    @Unique
    private boolean vanadium$defaultMakeBB;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$initMakeBBFlag(CallbackInfo ci) {
        this.vanadium$defaultMakeBB = VANADIUM$DEFAULT_MAKE_BB.get(this.getClass());
    }

    @WrapMethod(method = "checkInsideBlocks(Ljava/util/List;Lnet/minecraft/world/entity/InsideBlockEffectApplier$StepBasedCollector;)V")
    private void vanadium$checkInsideBlocks(
            List<Entity.Movement> movements,
            InsideBlockEffectApplier.StepBasedCollector collector,
            Operation<Void> original) {
        if (!this.isAffectedByBlocks()) {
            return;
        }
        Level level = this.level;
        if (level instanceof ServerLevel serverLevel
                && serverLevel.getServer().debugSubscribers().hasAnySubscriberFor(DebugSubscriptions.ENTITY_BLOCK_INTERSECTIONS)) {
            original.call(movements, collector);
            return;
        }
        InsideBlockFastPath.checkInsideBlocks(
                (Entity) (Object) this,
                level,
                movements,
                collector,
                this.visitedBlocks,
                this.vanadium$defaultMakeBB);
    }
}
