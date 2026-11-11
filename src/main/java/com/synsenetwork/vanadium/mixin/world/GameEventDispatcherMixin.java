package com.synsenetwork.vanadium.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import java.util.List;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vibration listeners (sculk sensors, shriekers, wardens) record what they hear in their own
 * state, and several entity ticks can reach one listener at once. Delivery runs after the listener registries
 * have been visited, outside their monitors, so it can take the shared lock; it only happens near such listeners.
 */
@Mixin(GameEventDispatcher.class)
public abstract class GameEventDispatcherMixin {
    @WrapMethod(method = "handleGameEventMessagesInQueue")
    private void vanadium$deliver(List<GameEvent.ListenerInfo> messages, Operation<Void> original) {
        boolean scoped = Interactions.active && Interactions.enterShared(Interactions.Kind.EVENT);
        try {
            original.call(messages);
        } finally {
            if (scoped) Interactions.exit();
        }
    }
}
