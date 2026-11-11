package com.synsenetwork.vanadium.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;

/** Flat entity wave: an explosion set off by an entity tick changes blocks and many entities as one shared write. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @WrapMethod(method = "explode")
    private int vanadium$explode(Operation<Integer> original) {
        boolean scoped = Interactions.active && Interactions.enterShared(Interactions.Kind.EXPLOSION);
        try {
            return original.call();
        } finally {
            if (scoped) Interactions.exit();
        }
    }
}
