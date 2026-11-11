package com.synsenetwork.vanadium.mixin.world.poi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Section methods are synchronized ({@code SyncAllMixin}), but vanilla's record stream and Lithium's iterators
 * are consumed after the lock is released, while other regions add and remove POI in the section. The per-type
 * map and sets are concurrent, so those reads are weakly consistent instead of failing mid-iteration.
 */
@Mixin(PoiSection.class)
public abstract class PoiSectionMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Holder<PoiType>, Set<PoiRecord>> byType;

    @Unique
    private static final Function<Holder<PoiType>, Set<PoiRecord>> vanadium$newTypeSet = type -> ConcurrentHashMap.newKeySet();

    /** Replaces the initializer's map before the constructor adds any records. */
    @Redirect(method = "<init>(Ljava/lang/Runnable;ZLjava/util/List;)V", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiSection;byType:Ljava/util/Map;", opcode = Opcodes.PUTFIELD))
    private void vanadium$concurrentByType(PoiSection section, Map<Holder<PoiType>, Set<PoiRecord>> vanilla) {
        this.byType = new ConcurrentHashMap<>();
    }

    @WrapOperation(method = "add(Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;)Z", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object vanadium$concurrentTypeSet(Map<Holder<PoiType>, Set<PoiRecord>> byType, Object type, Function<?, ?> vanilla,
            Operation<Object> original) {
        return original.call(byType, type, vanadium$newTypeSet);
    }
}
