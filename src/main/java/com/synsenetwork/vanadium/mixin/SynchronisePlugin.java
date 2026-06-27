package com.synsenetwork.vanadium.mixin;

import com.synsenetwork.vanadium.compat.C2ME;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Gates FastUtilsMixin off under C2ME, and stamps ACC_SYNCHRONIZED on every instance method of the
 *  SYNC_ALL targets (non-thread-safe vanilla helpers Vanadium touches from worker threads). */
public class SynchronisePlugin implements IMixinConfigPlugin {
    private static final Logger syncLogger = LogManager.getLogger();

    private static final Set<String> SYNC_ALL = Set.of(
            "com.synsenetwork.vanadium.mixin.FastUtilsMixin",
            "com.synsenetwork.vanadium.mixin.SyncAllMixin");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        if (C2ME.LOADED) {
            return null;
        }
        return List.of("FastUtilsMixin");
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!SYNC_ALL.contains(mixinClassName)) {
            return;
        }
        int negFilter = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_BRIDGE;
        for (MethodNode method : targetClass.methods) {
            if ((method.access & negFilter) == 0 && !method.name.equals("<init>")) {
                method.access |= Opcodes.ACC_SYNCHRONIZED;
                if (!mixinClassName.equals("com.synsenetwork.vanadium.mixin.FastUtilsMixin")) {
                    logSyncBit(method.name, targetClassName);
                }
            }
        }
    }

    private void logSyncBit(String methodName, String targetClassName) {
        syncLogger.info("Setting synchronize bit for {} in {}.", methodName, targetClassName);
    }
}
