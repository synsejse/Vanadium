package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.ItemLockAccess;
import com.synsenetwork.vanadium.tracking.NavigationState;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Mob.class)
public abstract class MobMixin implements NavigationState {
    @Unique private volatile boolean vanadium$navigationMember;
    @Unique private final AtomicBoolean vanadium$navigationDirty = new AtomicBoolean();

    @Override public boolean vanadium$navigationMember() { return vanadium$navigationMember; }
    @Override public void vanadium$setNavigationMember(boolean member) { vanadium$navigationMember = member; }
    @Override public AtomicBoolean vanadium$navigationDirty() { return vanadium$navigationDirty; }

    @WrapMethod(method = "pickUpItem")
    private void pickup(ServerLevel level, ItemEntity item, Operation<Void> original) {
        ReentrantLock lock = ((ItemLockAccess) item).vanadium$itemLock();
        lock.lock();
        try {
            if (!item.isRemoved()) original.call(level, item);
        } finally { lock.unlock(); }
    }
}
