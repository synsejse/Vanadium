package com.synsenetwork.vanadium.mixin.entity.ai.brain.task;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ShowTradesToPlayer.class)
public class ShowTradesToPlayerMixin {
    @Shadow
    private final List<ItemStack> displayItems = new CopyOnWriteArrayList<>();
}
