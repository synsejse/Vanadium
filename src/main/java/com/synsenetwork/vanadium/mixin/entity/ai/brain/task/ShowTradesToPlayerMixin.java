package com.synsenetwork.vanadium.mixin.entity.ai.brain.task;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShowTradesToPlayer.class)
public class ShowTradesToPlayerMixin {
    @Shadow
    @Final
    @Mutable
    private List<ItemStack> displayItems;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void installConcurrentDisplayItems(CallbackInfo ci) {
        displayItems = new CopyOnWriteArrayList<>();
    }
}
