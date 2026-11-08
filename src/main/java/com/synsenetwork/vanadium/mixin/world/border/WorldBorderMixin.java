package com.synsenetwork.vanadium.mixin.world.border;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * {@code ServerLevel.getWorldBorder()} applies initial settings on every call, from every worker.
 * Initialization happens once on the server thread at level load; later calls only read the flag.
 * Other border state is written by the server thread (ticking, commands) and read racily by workers,
 * as vanilla readers on other threads would observe it.
 */
@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {
    @Shadow private boolean initialized;

    @WrapMethod(method = "applyInitialSettings")
    private void initializeOnce(long gameTime, Operation<Void> original) {
        if (initialized) return;
        synchronized (this) {
            original.call(gameTime);
        }
    }
}
