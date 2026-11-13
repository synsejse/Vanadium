package com.synsenetwork.vanadium.mixin.world.entity;

import com.synsenetwork.vanadium.entity.CrowdSlotAccess;
import com.synsenetwork.vanadium.entity.CrowdedSections;
import com.synsenetwork.vanadium.entity.CrowdedSectionsAccess;
import com.synsenetwork.vanadium.entity.EntitySectionIndex;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import net.caffeinemc.mods.lithium.common.world.ChunkAwareEntityIterable;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.ChunkPos;
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
 *
 * <p>Lithium mixins that read vanilla's storage are served from the index too: sections are still built
 * by {@code createSection}, where Lithium's {@code util.entity_section_position} records each section's key
 * (its movement trackers remove emptied sections by that key), and the chunk-aware spawn-count iteration of
 * {@code minimal_nonvanilla.spawning}, whose own storage mixin is cancelled in {@code compat/Lithium}, is
 * implemented here.
 *
 * <p>Each section is linked to this storage's {@link CrowdedSections}, which indexes crowded sections for box
 * queries; the server's entity manager refreshes it after each level tick.
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> implements ChunkAwareEntityIterable<T>, CrowdedSectionsAccess {
    @Shadow @Final private Class<T> entityClass;
    @Shadow @Final private Long2ObjectFunction<Visibility> intialSectionVisibility;

    @Unique
    private final EntitySectionIndex<T> vanadium$index = new EntitySectionIndex<>();

    @Unique
    private final CrowdedSections<T> vanadium$crowds = new CrowdedSections<>();

    @Override
    public CrowdedSections<?> vanadium$crowds() {
        return vanadium$crowds;
    }

    @Override
    public void vanadium$refreshCrowds(boolean enabled) {
        vanadium$crowds.refresh(enabled, vanadium$index::get);
    }

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
        return Arrays.stream(vanadium$index.sectionsInChunk(chunkKey));
    }

    /**
     * @author Vanadium
     * @reason Lock-free hit path; creation serializes inside the index.
     */
    @Overwrite
    public EntitySection<T> getOrCreateSection(long key) {
        EntitySection<T> section = vanadium$index.get(key);
        return section != null ? section : vanadium$index.getOrCreate(key, this::createSection);
    }

    /**
     * @author Vanadium
     * @reason Served from the section index.
     */
    @Overwrite
    public @Nullable EntitySection<T> getSection(long key) {
        return vanadium$index.get(key);
    }

    /**
     * @author Vanadium
     * @reason Builds the section without vanilla's sorted key set; the index publishes it. Runs under the
     * index's writer lock, so injections here (Lithium's position) finish before any reader sees the section.
     */
    @Overwrite
    private EntitySection<T> createSection(long sectionKey) {
        long chunk = ChunkPos.pack(SectionPos.x(sectionKey), SectionPos.z(sectionKey));
        EntitySection<T> section = new EntitySection<>(entityClass, intialSectionVisibility.get(chunk));
        @SuppressWarnings("unchecked")
        CrowdSlotAccess<T> slot = (CrowdSlotAccess<T>) section;
        slot.vanadium$setCrowdSlot(vanadium$crowds.slot(section, sectionKey));
        return section;
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

    /** Lithium's spawn counting: entities of accessible non-empty sections, grouped by chunk column. */
    @Override
    public Iterable<T> lithium$IterateEntitiesInTrackedSections() {
        return vanadium$index.accessibleEntities();
    }
}
