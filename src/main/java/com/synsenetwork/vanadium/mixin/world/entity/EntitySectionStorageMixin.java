package com.synsenetwork.vanadium.mixin.world.entity;

import com.synsenetwork.vanadium.entity.EntitySectionIndex;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Every section access goes through a lock-free {@link EntitySectionIndex}; vanilla's hash map and AVL
 * tree stay empty. Entity queries run on every worker for every moving mob, so lookups must neither
 * lock nor box keys, and box queries visit only the chunk columns inside the box.
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
    @Shadow @Final private Class<T> entityClass;
    @Shadow @Final private Long2ObjectFunction<Visibility> intialSectionVisibility;

    @Unique
    private final EntitySectionIndex<T> vanadium$index = new EntitySectionIndex<>();

    /**
     * @author Vanadium
     * @reason Lock-free column-indexed queries in vanilla's section order.
     */
    @Overwrite
    public void forEachAccessibleNonEmptySection(AABB bb, AbortableIterationConsumer<EntitySection<T>> output) {
        vanadium$index.forEachAccessibleNonEmpty(
                SectionPos.posToSectionCoord(bb.minX - 2.0), SectionPos.posToSectionCoord(bb.minY - 4.0),
                SectionPos.posToSectionCoord(bb.minZ - 2.0), SectionPos.posToSectionCoord(bb.maxX + 2.0),
                SectionPos.posToSectionCoord(bb.maxY + 0.0), SectionPos.posToSectionCoord(bb.maxZ + 2.0), output);
    }

    /**
     * @author Vanadium
     * @reason Served from the section index.
     */
    @Overwrite
    public LongStream getExistingSectionPositionsInChunk(long chunkKey) {
        return Arrays.stream(vanadium$index.sectionKeysInChunk(chunkKey));
    }

    /**
     * @author Vanadium
     * @reason Served from the section index.
     */
    @Overwrite
    public Stream<EntitySection<T>> getExistingSectionsInChunk(long chunkKey) {
        return Arrays.stream(vanadium$index.sectionKeysInChunk(chunkKey)).mapToObj(vanadium$index::get).filter(Objects::nonNull);
    }

    /**
     * @author Vanadium
     * @reason Lock-free hit path; creation serializes inside the index.
     */
    @Overwrite
    public EntitySection<T> getOrCreateSection(long key) {
        EntitySection<T> section = vanadium$index.get(key);
        return section != null ? section : vanadium$index.getOrCreate(key, this::vanadium$newSection);
    }

    /**
     * @author Vanadium
     * @reason Served from the section index.
     */
    @Overwrite
    public @Nullable EntitySection<T> getSection(long key) {
        return vanadium$index.get(key);
    }

    @Unique
    private EntitySection<T> vanadium$newSection(long sectionKey) {
        long chunk = net.minecraft.world.level.ChunkPos.pack(SectionPos.x(sectionKey), SectionPos.z(sectionKey));
        return new EntitySection<>(entityClass, intialSectionVisibility.get(chunk));
    }

    /**
     * @author Vanadium
     * @reason Served from the section index.
     */
    @Overwrite
    public LongSet getAllChunksWithExistingSections() {
        return vanadium$index.chunksWithSections();
    }

    /**
     * @author Vanadium
     * @reason Served from the section index.
     */
    @Overwrite
    public void remove(long sectionKey) {
        vanadium$index.remove(sectionKey);
    }

    /**
     * @author Vanadium
     * @reason Served from the section index.
     */
    @Overwrite
    public int count() {
        return vanadium$index.size();
    }
}
