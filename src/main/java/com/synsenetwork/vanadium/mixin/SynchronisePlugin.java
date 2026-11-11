package com.synsenetwork.vanadium.mixin;

import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Stamps ACC_SYNCHRONIZED on every instance method of the SYNC_ALL targets (non-thread-safe vanilla
 *  helpers Vanadium touches from worker threads), and makes {@code Level.randValue} per-thread in the
 *  RAND_VALUE targets. Both run after every mixin, so they also cover other mods' merged code. */
public class SynchronisePlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String SYNC_ALL = "com.synsenetwork.vanadium.mixin.SyncAllMixin";
    private static final String RAND_VALUE = "com.synsenetwork.vanadium.mixin.world.RandValueMixin";
    private static final Set<String> RAND_VALUE_OWNERS = Set.of("net/minecraft/world/level/Level", "net/minecraft/server/level/ServerLevel");
    private static final String RAND_VALUE_STATE = "com/synsenetwork/vanadium/concurrent/PerThreadRandValue";
    /** Methods that only read volatile state; locking them would serialize hot read paths. */
    private static final Set<String> UNSYNCHRONIZED = Set.of(
            "net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint#hasWork");

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
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (RAND_VALUE.equals(mixinClassName)) {
            perThreadRandValue(targetClassName, targetClass);
            return;
        }
        if (!SYNC_ALL.equals(mixinClassName)) {
            return;
        }
        int excludedMethodFlags = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_BRIDGE;
        for (MethodNode method : targetClass.methods) {
            if ((method.access & excludedMethodFlags) == 0 && !method.name.equals("<init>")
                    && !method.name.startsWith("vanadium$")
                    && !UNSYNCHRONIZED.contains(targetClassName + "#" + method.name)) {
                method.access |= Opcodes.ACC_SYNCHRONIZED;
                LOGGER.info("Setting synchronize bit for {} in {}.", method.name, targetClassName);
            }
        }
    }

    /** Replaces field accesses outside constructors (which only seed the shared field) with per-thread state. */
    private static void perThreadRandValue(String targetClassName, ClassNode targetClass) {
        int rewritten = 0;
        for (MethodNode method : targetClass.methods) {
            if (method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof FieldInsnNode field && field.name.equals("randValue") && field.desc.equals("I")
                        && RAND_VALUE_OWNERS.contains(field.owner)
                        && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.PUTFIELD)) {
                    boolean get = field.getOpcode() == Opcodes.GETFIELD;
                    method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, RAND_VALUE_STATE,
                            get ? "get" : "set", get ? "(Lnet/minecraft/world/level/Level;)I" : "(Lnet/minecraft/world/level/Level;I)V", false));
                    rewritten++;
                }
                insn = next;
            }
        }
        LOGGER.info("Made {} randValue accesses per-thread in {}.", rewritten, targetClassName);
    }
}
