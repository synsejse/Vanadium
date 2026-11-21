package com.synsenetwork.vanadium.mixin.world.entity.ai.sensing;

import com.synsenetwork.vanadium.concurrent.StableDistance;
import java.util.Comparator;
import java.util.List;
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
    @SuppressWarnings("unchecked")
    @Redirect(method = "doTick", at = @At(value = "INVOKE",
            target = "Ljava/util/Comparator;comparingDouble(Ljava/util/function/ToDoubleFunction;)Ljava/util/Comparator;"))
    private Comparator<LivingEntity> vanadium$skipDistanceComparator(ToDoubleFunction<? super LivingEntity> distance) {
        return (Comparator<LivingEntity>) (Comparator<?>) StableDistance.UNUSED;
    }

    @Redirect(method = "doTick", at = @At(value = "INVOKE",
            target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"))
    private void vanadium$stableSort(List<LivingEntity> list, Comparator<? super LivingEntity> comparator, ServerLevel level, T body) {
        StableDistance.sortByDistance(list, body);
    }
}
