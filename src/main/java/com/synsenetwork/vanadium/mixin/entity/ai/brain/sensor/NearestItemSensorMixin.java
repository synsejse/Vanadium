package com.synsenetwork.vanadium.mixin.entity.ai.brain.sensor;

import com.synsenetwork.vanadium.concurrent.StableDistance;
import java.util.Comparator;
import java.util.function.ToDoubleFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.NearestItemSensor;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Items fall and drift in their own ticks while this sensor sorts them; see {@link StableDistance}. */
@Mixin(NearestItemSensor.class)
public class NearestItemSensorMixin {
    @Redirect(method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;)V", at = @At(value = "INVOKE",
            target = "Ljava/util/Comparator;comparingDouble(Ljava/util/function/ToDoubleFunction;)Ljava/util/Comparator;"))
    private Comparator<ItemEntity> vanadium$stableOrder(ToDoubleFunction<? super ItemEntity> distance, ServerLevel level, Mob body) {
        return StableDistance.comparator(body);
    }
}
