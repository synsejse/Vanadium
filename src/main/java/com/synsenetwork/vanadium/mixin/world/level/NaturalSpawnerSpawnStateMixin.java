package com.synsenetwork.vanadium.mixin.world.level;

import com.synsenetwork.vanadium.spawning.ConcurrentMobCategoryCounts;
import com.synsenetwork.vanadium.spawning.SpawnStateAccess;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lock-free read path for {@link NaturalSpawner.SpawnState}, replacing whole-class synchronization in
 * {@code SyncAllMixin}. Parallel spawning workers read global/local caps and potential charges without taking
 * a shared monitor; per-thread charge caching avoids racing on {@code lastCheckedPos/Type/Charge}, and only
 * the rare {@code afterSpawn} update synchronizes the fastutil {@code mobCategoryCounts} view.
 */
@Mixin(NaturalSpawner.SpawnState.class)
public abstract class NaturalSpawnerSpawnStateMixin implements SpawnStateAccess {
    @Shadow @Final private int spawnableChunkCount;
    @Shadow @Final private Object2IntOpenHashMap<MobCategory> mobCategoryCounts;
    @Shadow @Final private PotentialCalculator spawnPotential;
    @Shadow @Final private LocalMobCapCalculator localMobCapCalculator;

    @Unique
    private AtomicIntegerArray vanadium$categoryCounts;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$initCategoryCounts(
            int spawnableChunkCount,
            Object2IntOpenHashMap<MobCategory> mobCategoryCounts,
            PotentialCalculator spawnPotential,
            LocalMobCapCalculator localMobCapCalculator,
            CallbackInfo ci) {
        this.vanadium$categoryCounts = new AtomicIntegerArray(MobCategory.values().length);
        vanadium$syncCategoryCounts();
    }

    @Override
    public void vanadium$syncCategoryCounts() {
        for (MobCategory category : MobCategory.values()) {
            this.vanadium$categoryCounts.set(category.ordinal(), this.mobCategoryCounts.getInt(category));
        }
    }

    /**
     * @author Vanadium
     * @reason Lock-free global category cap check.
     */
    @Overwrite
    private boolean canSpawnForCategoryGlobal(MobCategory category) {
        int limit = category.getMaxInstancesPerChunk() * this.spawnableChunkCount / NaturalSpawner.MAGIC_NUMBER;
        return this.vanadium$categoryCounts.get(category.ordinal()) < limit;
    }

    /**
     * @author Vanadium
     * @reason Lock-free spawn-potential check using a per-thread 1-entry charge cache.
     */
    @Overwrite
    private boolean canSpawn(EntityType<?> type, Level level, BlockPos pos, ChunkAccess chunk) {
        ConcurrentMobCategoryCounts.SpawnChargeCache cache = ConcurrentMobCategoryCounts.chargeCache();
        cache.pos = pos;
        cache.type = type;
        MobSpawnSettings.MobSpawnCost cost = level.environmentAttributes()
                .getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos)
                .getMobSpawnCost(type);
        if (cost == null) {
            cache.charge = 0.0;
            return true;
        }
        double charge = cost.charge();
        cache.charge = charge;
        return this.spawnPotential.getPotentialEnergyChange(pos, charge) <= cost.energyBudget();
    }

    /**
     * @author Vanadium
     * @reason Updates lock-free counters, potential charges, and local caps without blocking concurrent readers.
     */
    @Overwrite
    private void afterSpawn(Mob mob, ChunkAccess chunk) {
        EntityType<?> type = mob.getType();
        BlockPos pos = mob.blockPosition();
        ConcurrentMobCategoryCounts.SpawnChargeCache cache = ConcurrentMobCategoryCounts.chargeCache();
        double charge;
        if (pos.equals(cache.pos) && type == cache.type) {
            charge = cache.charge;
        } else {
            MobSpawnSettings.MobSpawnCost cost = mob.level().environmentAttributes()
                    .getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos)
                    .getMobSpawnCost(type);
            charge = cost != null ? cost.charge() : 0.0;
        }
        this.spawnPotential.addCharge(pos, charge);
        MobCategory category = type.getCategory();
        this.vanadium$categoryCounts.incrementAndGet(category.ordinal());
        synchronized (this.mobCategoryCounts) {
            this.mobCategoryCounts.addTo(category, 1);
        }
        this.localMobCapCalculator.addMob(ChunkPos.containing(pos), category);
    }
}
