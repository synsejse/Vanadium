package com.synsenetwork.vanadium.mixin.util.collection;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClassInstanceMultiMap.class)
public abstract class ClassInstanceMultiMapMixin<T> {
    @Shadow
    @Final
    @Mutable
    private Map<Class<?>, List<T>> byClass = new ConcurrentHashMap<>();

    @Shadow
    @Final
    @Mutable
    private List<T> allInstances = new CopyOnWriteArrayList<>();

    @ModifyArg(method = "lambda$find$0", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"))
    private <E> Collector<E, ?, List<E>> overwriteCollectToList(Collector<E, ?, List<E>> collector) {
        return Collectors.toCollection(CopyOnWriteArrayList::new);
    }
}