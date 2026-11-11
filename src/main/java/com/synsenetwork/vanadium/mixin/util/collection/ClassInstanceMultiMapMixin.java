package com.synsenetwork.vanadium.mixin.util.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.caffeinemc.mods.lithium.common.entity.EntityClassGroup;
import net.caffeinemc.mods.lithium.common.world.chunk.ClassGroupFilterableList;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity sections are read by every AI query on every worker while entities move between them.
 * Per-class lists are copy-on-write and are never replaced, so {@link #find} returns one cached
 * read-only view per class: a hit takes no lock and allocates nothing, where vanilla allocated a
 * capturing lambda and a wrapper on every call (about 9% of all allocation with thousands of mobs).
 * Adds, removes and the first lookup of a class serialize, so a list built for a new class cannot
 * miss an entity added concurrently.
 *
 * <p>Also provides Lithium's entity class groups (in place of Lithium's own single-threaded lists, whose
 * mixin {@code compat.Lithium} cancels) with the same copy-on-write rules. Lithium's entity collision,
 * cramming and projectile-collision shortcuts query them; without them those shortcuts stay off.
 */
@Mixin(ClassInstanceMultiMap.class)
public abstract class ClassInstanceMultiMapMixin<T> implements ClassGroupFilterableList<T> {
    @Shadow
    @Final
    @Mutable
    private Map<Class<?>, List<T>> byClass;

    @Shadow
    @Final
    @Mutable
    private List<T> allInstances;

    @Shadow @Final private Class<T> baseClass;

    @Unique
    private Map<Class<?>, Collection<?>> vanadium$views;

    @Unique
    private Map<EntityClassGroup, List<T>> vanadium$groups;

    @Unique
    private Map<EntityClassGroup, Collection<T>> vanadium$groupViews;

    /** Installed explicitly; see AGENTS.md on shadowed field initializers. The map is still empty here. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$installConcurrentLists(Class<T> baseClass, CallbackInfo ci) {
        this.allInstances = new CopyOnWriteArrayList<>();
        this.byClass = new ConcurrentHashMap<>();
        this.byClass.put(baseClass, this.allInstances);
        this.vanadium$views = new ConcurrentHashMap<>();
        this.vanadium$groups = new ConcurrentHashMap<>();
        this.vanadium$groupViews = new ConcurrentHashMap<>();
    }

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
            if (!vanadium$groups.isEmpty()) {
                for (Map.Entry<EntityClassGroup, List<T>> entry : vanadium$groups.entrySet()) {
                    if (entry.getKey().contains((Entity) instance)) entry.getValue().add(instance);
                }
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
            if (!vanadium$groups.isEmpty()) {
                for (List<T> members : vanadium$groups.values()) members.remove(object);
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

    /** Lithium's class-group lookup; same lock-free hits and serialized creation as {@link #find}. */
    @Override
    public Collection<T> lithium$getAllOfGroupType(EntityClassGroup group) {
        Collection<T> view = vanadium$groupViews.get(group);
        if (view != null) return view;
        synchronized (this) {
            view = vanadium$groupViews.get(group);
            if (view == null) {
                List<T> matching = new ArrayList<>();
                for (T instance : allInstances) {
                    if (group.contains((Entity) instance)) matching.add(instance);
                }
                List<T> members = new CopyOnWriteArrayList<>(matching);
                vanadium$groups.put(group, members);
                view = Collections.unmodifiableCollection(members);
                vanadium$groupViews.put(group, view);
            }
            return view;
        }
    }
}
