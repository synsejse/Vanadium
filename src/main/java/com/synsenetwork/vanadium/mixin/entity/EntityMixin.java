package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.InteractionAccess;
import com.synsenetwork.vanadium.concurrent.Interactions;
import java.util.concurrent.atomic.AtomicReference;
import com.synsenetwork.vanadium.concurrent.OwnerLocks;
import com.synsenetwork.vanadium.entity.CrowdAccess;
import com.synsenetwork.vanadium.entity.CrowdedSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import com.synsenetwork.vanadium.tracking.NavigationIndex;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin implements InteractionAccess, CrowdAccess {
    @Unique
    private final OwnerLocks.Lock vanadium$tickLock = new OwnerLocks.Lock();

    @Unique
    private final AtomicReference<Vec3> vanadium$impulse = new AtomicReference<>();

    @Override
    public OwnerLocks.Lock vanadium$tickLock() {
        return vanadium$tickLock;
    }

    @Override
    public AtomicReference<Vec3> vanadium$impulse() {
        return vanadium$impulse;
    }

    @Unique
    private int vanadium$failedTicks;

    @Override
    public int vanadium$failedTicks() {
        return vanadium$failedTicks;
    }

    @Override
    public void vanadium$setFailedTicks(int ticks) {
        vanadium$failedTicks = ticks;
    }

    @Unique
    private volatile CrowdedSection.@Nullable Member<?> vanadium$crowd;

    @Override
    public CrowdedSection.@Nullable Member<?> vanadium$crowd() {
        return vanadium$crowd;
    }

    @Override
    public void vanadium$setCrowd(CrowdedSection.@Nullable Member<?> member) {
        vanadium$crowd = member;
    }

    /**
     * Keeps a crowded section's cells in step with this box ({@link CrowdedSection}). Outside crowded sections
     * this is one field read. Inside, a write from another tick waits for this entity's own tick as a position
     * write does, so one entity's box moves never overlap; a box that stays in its cells takes no lock.
     */
    @WrapMethod(method = "setBoundingBox")
    private void vanadium$setBoundingBox(AABB bb, Operation<Void> original) {
        if (vanadium$crowd == null) {
            original.call(bb);
            return;
        }
        boolean scoped = Interactions.active && Interactions.enter((Entity) (Object) this, Interactions.Kind.POSITION);
        try {
            vanadium$moveInCells(bb, original);
        } finally {
            if (scoped) Interactions.exit();
        }
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void vanadium$moveInCells(AABB bb, Operation<Void> original) {
        CrowdedSection.Member member = vanadium$crowd;
        if (member == null) {
            original.call(bb);
            return;
        }
        CrowdedSection grid = member.grid;
        int range = grid.rangeOf(bb);
        if (range == member.range || !grid.beginMove(member, range)) {
            original.call(bb);
            return;
        }
        try {
            original.call(bb);
        } finally {
            grid.endMove(member);
        }
    }

    /**
     * A push from another entity's tick goes to this entity's inbox, so crowds never wait on
     * each other and no push is lost to this entity's own movement update running at the same time.
     */
    @WrapMethod(method = "push(DDD)V")
    private void vanadium$push(double x, double y, double z, Operation<Void> original) {
        if (Interactions.active && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Interactions.deferPush((Entity) (Object) this, x, y, z)) {
            return;
        }
        original.call(x, y, z);
    }

    /** Velocity, position and removal of another tick's entity wait for that tick. */
    @WrapMethod(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V")
    private void vanadium$setDeltaMovement(Vec3 movement, Operation<Void> original) {
        if (Interactions.active && Interactions.enter((Entity) (Object) this, Interactions.Kind.VELOCITY)) {
            try {
                original.call(movement);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(movement);
        }
    }

    @WrapMethod(method = "setPosRaw(DDD)V")
    private void vanadium$setPosRaw(double x, double y, double z, Operation<Void> original) {
        if (Interactions.active && Interactions.enter((Entity) (Object) this, Interactions.Kind.POSITION)) {
            try {
                original.call(x, y, z);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(x, y, z);
        }
    }

    @WrapMethod(method = "setRemoved")
    private void vanadium$setRemoved(Entity.RemovalReason reason, Operation<Void> original) {
        if (Interactions.active && Interactions.enter((Entity) (Object) this, Interactions.Kind.REMOVE)) {
            try {
                original.call(reason);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(reason);
        }
    }

    @Inject(method = "setPosRaw", at = @At("HEAD"))
    private void invalidateNavigationPosition(double x, double y, double z, CallbackInfo ci) {
        NavigationIndex.invalidate((Entity) (Object) this);
    }

    /** Mounting joins two vehicle trees, so it owns both; one of them is the caller's. */
    @WrapMethod(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z")
    private boolean vanadium$startRiding(Entity vehicle, boolean force, boolean events, Operation<Boolean> original) {
        if (!Interactions.active) return original.call(vehicle, force, events);
        boolean rider = Interactions.enter((Entity) (Object) this, Interactions.Kind.OTHER);
        boolean ridden = false;
        try {
            ridden = Interactions.enter(vehicle, Interactions.Kind.OTHER);
            return original.call(vehicle, force, events);
        } finally {
            if (ridden) Interactions.exit();
            if (rider) Interactions.exit();
        }
    }
}
