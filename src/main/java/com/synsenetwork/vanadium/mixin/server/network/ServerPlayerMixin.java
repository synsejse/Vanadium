package com.synsenetwork.vanadium.mixin.server.network;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @WrapMethod(method = "die")
    private synchronized void onDead(DamageSource damageSource, Operation<Void> original) {
        original.call(damageSource);
    }
}
