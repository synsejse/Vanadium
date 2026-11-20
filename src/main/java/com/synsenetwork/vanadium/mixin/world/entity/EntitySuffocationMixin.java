package com.synsenetwork.vanadium.mixin.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's {@code isInWall}, which every living entity runs every tick, streams the blocks of its eye box
 * through {@code BlockPos.betweenClosedStream(...).anyMatch(...)}: about ten objects per call (eye position,
 * box, stream pipeline, spliterator, iterator, cursor, predicate, sink). This is the same test as a loop: the
 * same box, the same positions in the same order (x fastest, then y, then z), the same block-state checks and
 * the same early exit. The eye box becomes a shape only once a suffocating block is found.
 *
 * <p>Lithium's cached variant ({@code experimental.entity.block_caching.suffocation}, under the experimental
 * options that are off by default) is disabled in {@code lithium:options}. If a user enables it anyway, its
 * injection still applies but sits after this one, which always returns first.
 */
@Mixin(Entity.class)
public abstract class EntitySuffocationMixin {
    @Shadow public boolean noPhysics;
    @Shadow private EntityDimensions dimensions;

    @Shadow public abstract Level level();

    @Shadow public abstract double getX();

    @Shadow public abstract double getEyeY();

    @Shadow public abstract double getZ();

    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
    private void vanadium$isInWall(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(!this.noPhysics && this.vanadium$eyeInWall());
    }

    @Unique
    private boolean vanadium$eyeInWall() {
        float checkWidth = this.dimensions.width() * 0.8F;
        double x = this.getX();
        double y = this.getEyeY();
        double z = this.getZ();
        // AABB.ofSize(getEyePosition(), checkWidth, 1.0E-6, checkWidth), including the constructor's min/max.
        double x0 = x - checkWidth / 2.0, y0 = y - 1.0E-6 / 2.0, z0 = z - checkWidth / 2.0;
        double x1 = x + checkWidth / 2.0, y1 = y + 1.0E-6 / 2.0, z1 = z + checkWidth / 2.0;
        double minX = Math.min(x0, x1), minY = Math.min(y0, y1), minZ = Math.min(z0, z1);
        double maxX = Math.max(x0, x1), maxY = Math.max(y0, y1), maxZ = Math.max(z0, z1);
        int fromX = Mth.floor(minX), fromY = Mth.floor(minY), fromZ = Mth.floor(minZ);
        int toX = Mth.floor(maxX), toY = Mth.floor(maxY), toZ = Mth.floor(maxZ);
        Level level = this.level();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        @Nullable VoxelShape eye = null;
        for (int bz = fromZ; bz <= toZ; bz++) {
            for (int by = fromY; by <= toY; by++) {
                for (int bx = fromX; bx <= toX; bx++) {
                    pos.set(bx, by, bz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !state.isSuffocating(level, pos)) continue;
                    if (eye == null) eye = Shapes.create(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
                    if (Shapes.joinIsNotEmpty(state.getCollisionShape(level, pos).move(pos), eye, BooleanOp.AND)) return true;
                }
            }
        }
        return false;
    }
}
