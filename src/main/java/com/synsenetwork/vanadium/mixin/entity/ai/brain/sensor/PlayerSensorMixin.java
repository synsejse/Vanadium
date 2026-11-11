package com.synsenetwork.vanadium.mixin.entity.ai.brain.sensor;

import com.synsenetwork.vanadium.concurrent.StableDistance;
import java.util.Comparator;
import java.util.function.ToDoubleFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.PlayerSensor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Players riding vehicles move in those vehicles' ticks while this sensor sorts them; see {@link StableDistance}. */
@Mixin(PlayerSensor.class)
public class PlayerSensorMixin {
    @Redirect(method = "doTick", at = @At(value = "INVOKE",
            target = "Ljava/util/Comparator;comparingDouble(Ljava/util/function/ToDoubleFunction;)Ljava/util/Comparator;"))
    private Comparator<Player> vanadium$stableOrder(ToDoubleFunction<? super Player> distance, ServerLevel level, LivingEntity body) {
        return StableDistance.comparator(body);
    }
}
