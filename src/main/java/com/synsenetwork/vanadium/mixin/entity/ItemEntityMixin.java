package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.ItemLockAccess;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin implements ItemLockAccess {
    @Unique
    private final ReentrantLock vanadium$lock = new ReentrantLock();

    @Override public ReentrantLock vanadium$itemLock() { return vanadium$lock; }

    @WrapMethod(method = "tryToMerge")
    private void tryMerge(ItemEntity other, Operation<Void> original) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self == other) return;
        ReentrantLock otherLock = ((ItemLockAccess) other).vanadium$itemLock();
        ReentrantLock first = self.getId() < other.getId() ? vanadium$lock : otherLock;
        ReentrantLock second = self.getId() < other.getId() ? otherLock : vanadium$lock;
        first.lock();
        try {
            second.lock();
            try {
                // Selection happened before locking; another merger or pickup may have consumed either item.
                if (self.isMergable() && other.isMergable()) original.call(other);
            } finally { second.unlock(); }
        } finally { first.unlock(); }
    }

    @WrapMethod(method = "playerTouch")
    private void playerPickup(Player player, Operation<Void> original) {
        ReentrantLock lock = vanadium$lock;
        lock.lock();
        try {
            if (!((ItemEntity) (Object) this).isRemoved()) original.call(player);
        } finally { lock.unlock(); }
    }
}
