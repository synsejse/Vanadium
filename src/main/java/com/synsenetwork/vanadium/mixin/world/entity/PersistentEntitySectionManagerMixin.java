package com.synsenetwork.vanadium.mixin.world.entity;

import com.synsenetwork.vanadium.Vanadium;
import com.synsenetwork.vanadium.entity.CrowdedSectionsAccess;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crowded sections get their box-query grids here: on the server thread after the level's entity and block
 * entity ticks, when no entity box is being written (see {@code CrowdedSections}).
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> {
    @Shadow @Final private EntitySectionStorage<T> sectionStorage;

    @Inject(method = "tick", at = @At("TAIL"))
    private void vanadium$refreshCrowdedSections(CallbackInfo ci) {
        ((CrowdedSectionsAccess) sectionStorage).vanadium$refreshCrowds(Vanadium.config.crowdedSectionIndex);
    }
}
