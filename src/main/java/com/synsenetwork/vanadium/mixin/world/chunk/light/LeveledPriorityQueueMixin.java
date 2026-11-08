package com.synsenetwork.vanadium.mixin.world.chunk.light;

import com.synsenetwork.vanadium.concurrent.ConcurrentLongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.world.level.lighting.LeveledPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LeveledPriorityQueue.class)
public class LeveledPriorityQueueMixin {
    @Redirect(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/lighting/LeveledPriorityQueue;queues:[Lit/unimi/dsi/fastutil/longs/LongLinkedOpenHashSet;", args = "array=set"))
    private void overwritePendingIdUpdatesByLevel(LongLinkedOpenHashSet[] hashSets, int index, LongLinkedOpenHashSet hashSet) {
        hashSets[index] = new ConcurrentLongLinkedOpenHashSet();
    }
}
