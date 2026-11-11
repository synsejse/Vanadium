package com.synsenetwork.vanadium.mixin.entity.npc;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Gossiping copies the other villager's reputation map and stamps both villagers, and two
 * villagers gossip to each other in the same tick. The exchange owns both, so the map is never copied while
 * its owner changes it and the second exchange sees the first one's timestamp.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {
    @WrapMethod(method = "gossip")
    private void vanadium$gossip(ServerLevel level, Villager target, long time, Operation<Void> original) {
        if (Interactions.active && Interactions.enterPair(target, Interactions.Kind.PAIR)) {
            try {
                original.call(level, target, time);
            } finally {
                Interactions.exitPair();
            }
        } else {
            original.call(level, target, time);
        }
    }
}
