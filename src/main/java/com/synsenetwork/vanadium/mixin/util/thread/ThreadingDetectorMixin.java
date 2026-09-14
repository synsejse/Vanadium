package com.synsenetwork.vanadium.mixin.util.thread;

import java.util.concurrent.Semaphore;
import net.minecraft.util.ThreadingDetector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ThreadingDetector.class)
public abstract class ThreadingDetectorMixin {

    @Shadow
    @Final
    @Mutable
    private Semaphore lock = new Semaphore(255);
}
