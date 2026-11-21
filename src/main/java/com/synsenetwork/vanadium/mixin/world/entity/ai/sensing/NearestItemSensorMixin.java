package com.synsenetwork.vanadium.mixin.world.entity.ai.sensing;

import com.synsenetwork.vanadium.concurrent.StableDistance;
import java.util.Comparator;
import java.util.List;
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
    @SuppressWarnings("unchecked")
    @Redirect(method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;)V", at = @At(value = "INVOKE",
            target = "Ljava/util/Comparator;comparingDouble(Ljava/util/function/ToDoubleFunction;)Ljava/util/Comparator;"))
    private Comparator<ItemEntity> vanadium$skipDistanceComparator(ToDoubleFunction<? super ItemEntity> distance) {
        return (Comparator<ItemEntity>) (Comparator<?>) StableDistance.UNUSED;
    }

    @Redirect(method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;)V", at = @At(value = "INVOKE",
            target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"))
    private void vanadium$stableSort(List<ItemEntity> list, Comparator<? super ItemEntity> comparator, ServerLevel level, Mob body) {
        StableDistance.sortByDistance(list, body);
    }
}
