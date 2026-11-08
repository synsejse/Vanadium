package com.synsenetwork.vanadium.mixin.util.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Entity sections are read by every AI query on every worker while entities move between them.
 * Per-class lists are copy-on-write and are never replaced, so {@link #find} returns one cached
 * read-only view per class: a hit takes no lock and allocates nothing, where vanilla allocated a
 * capturing lambda and a wrapper on every call (about 9% of all allocation with thousands of mobs).
 * Adds, removes and the first lookup of a class serialize, so a list built for a new class cannot
 * miss an entity added concurrently.
 */
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

    @Shadow @Final private Class<T> baseClass;

    @Unique
    private final Map<Class<?>, Collection<?>> vanadium$views = new ConcurrentHashMap<>();

    /**
     * @author Vanadium
     * @reason Serialized with class-list creation.
     */
    @Overwrite
    public boolean add(T instance) {
        synchronized (this) {
            boolean success = false;
            for (Map.Entry<Class<?>, List<T>> entry : byClass.entrySet()) {
                if (entry.getKey().isInstance(instance)) success |= entry.getValue().add(instance);
            }
            return success;
        }
    }

    /**
     * @author Vanadium
     * @reason Serialized with class-list creation.
     */
    @Overwrite
    public boolean remove(Object object) {
        synchronized (this) {
            boolean success = false;
            for (Map.Entry<Class<?>, List<T>> entry : byClass.entrySet()) {
                if (entry.getKey().isInstance(object)) success |= entry.getValue().remove(object);
            }
            return success;
        }
    }

    /**
     * @author Vanadium
     * @reason Cached views: lock-free and allocation-free after the first lookup of a class.
     */
    @SuppressWarnings("unchecked")
    @Overwrite
    public <S> Collection<S> find(Class<S> index) {
        Collection<?> view = vanadium$views.get(index);
        if (view != null) return (Collection<S>) view;
        if (!baseClass.isAssignableFrom(index)) {
            throw new IllegalArgumentException("Don't know how to search for " + index);
        }
        synchronized (this) {
            view = vanadium$views.get(index);
            if (view == null) {
                List<T> instances = byClass.get(index);
                if (instances == null) {
                    List<T> matching = new ArrayList<>();
                    for (T instance : allInstances) {
                        if (index.isInstance(instance)) matching.add(instance);
                    }
                    instances = new CopyOnWriteArrayList<>(matching);
                    byClass.put(index, instances);
                }
                view = Collections.unmodifiableCollection(instances);
                vanadium$views.put(index, view);
            }
            return (Collection<S>) view;
        }
    }
}
