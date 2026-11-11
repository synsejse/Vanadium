package com.synsenetwork.vanadium.mixin.entity.ai.brain.sensor;

import com.synsenetwork.vanadium.concurrent.StableDistance;
import java.util.Comparator;
import java.util.function.ToDoubleFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Nearby mobs move in their own ticks while this sensor sorts them; see {@link StableDistance}. */
@Mixin(NearestLivingEntitySensor.class)
public class NearestLivingEntitySensorMixin<T extends LivingEntity> {
    @Redirect(method = "doTick", at = @At(value = "INVOKE",
            target = "Ljava/util/Comparator;comparingDouble(Ljava/util/function/ToDoubleFunction;)Ljava/util/Comparator;"))
    private Comparator<LivingEntity> vanadium$stableOrder(ToDoubleFunction<? super LivingEntity> distance, ServerLevel level, T body) {
        return StableDistance.comparator(body);
    }
}
