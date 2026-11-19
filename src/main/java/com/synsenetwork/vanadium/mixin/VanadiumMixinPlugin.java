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

/**
 * Vanadium's Mixin config plugin. After every mixin has been applied to a target (so other mods' merged code is
 * covered too), it stamps ACC_SYNCHRONIZED on every instance method of the {@code SyncAllMixin} targets
 * (non-thread-safe helpers Vanadium touches from worker threads), makes {@code Level.randValue} per-thread in the
 * {@code RandValueMixin} targets, and checks that the optional hooks into other mods' merged POI methods
 * (Lithium's {@code takeAt}, C2ME's {@code shouldUnloadPoi}) bound wherever their target exists.
 *
 * <p>Mixins are matched by class name: {@code VanadiumMixinPluginTest} checks the constants below against
 * {@code vanadium.mixins.json}.
 */
public class VanadiumMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger();

    static final String SYNC_ALL = "com.synsenetwork.vanadium.mixin.SyncAllMixin";
    static final String RAND_VALUE = "com.synsenetwork.vanadium.mixin.world.RandValueMixin";
    private static final Set<String> RAND_VALUE_OWNERS = Set.of("net/minecraft/world/level/Level", "net/minecraft/server/level/ServerLevel");
    static final String RAND_VALUE_STATE = "com/synsenetwork/vanadium/concurrent/PerThreadRandValue";
    static final String RAND_VALUE_GET = "(Lnet/minecraft/world/level/Level;)I";
    static final String RAND_VALUE_SET = "(Lnet/minecraft/world/level/Level;I)V";
    static final String POI_TAKE_AT = "com.synsenetwork.vanadium.mixin.compat.lithium.PoiTakeAtMixin";
    private static final String TAKE_AT = "lithium$takeAt";
    static final String TAKE_AT_HOOK = "vanadium$claimOrEmpty";
    static final String POI_UNLOAD = "com.synsenetwork.vanadium.mixin.compat.c2me.PoiUnloadMixin";
    private static final String SHOULD_UNLOAD_POI = "c2me$shouldUnloadPoi";
    static final String SHOULD_UNLOAD_POI_HOOK = "vanadium$keepResident";
    /**
     * Methods that only read volatile or concurrent state; locking them would serialize hot read paths.
     * {@code PoiSection.getRecords} builds a lazy stream over the concurrent per-type sets, consumed after the
     * monitor would be released anyway; it runs under the POI distance graph's monitor, which takes no other lock.
     */
    static final Set<String> UNSYNCHRONIZED = Set.of(
            "net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint#hasWork",
            "net.minecraft.world.entity.ai.village.poi.PoiSection#getRecords");

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
        switch (mixinClassName) {
            case SYNC_ALL -> synchronizeAll(targetClassName, targetClass);
            case RAND_VALUE -> perThreadRandValue(targetClassName, targetClass);
            case POI_TAKE_AT -> checkOptionalHook(targetClassName, targetClass, TAKE_AT, TAKE_AT_HOOK, "Lithium ai.poi disabled",
                    "a claim without a ticket would return the position (Lithium changed the method?)");
            case POI_UNLOAD -> checkOptionalHook(targetClassName, targetClass, SHOULD_UNLOAD_POI, SHOULD_UNLOAD_POI_HOOK,
                    "no C2ME chunk-system POI unloading", "C2ME could unload POI mid-wave or drop village centres (C2ME changed the method?)");
            default -> {
            }
        }
    }

    private static void synchronizeAll(String targetClassName, ClassNode targetClass) {
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

    /**
     * Hooks into a method another mod merges are {@code require = 0} only because that mod's option or module may
     * be off, leaving no method: Lithium's {@code ai.poi} adds {@code takeAt}, C2ME's chunk-system rewrite adds
     * {@code shouldUnloadPoi}. With the method present, an unbound hook would silently lose the guarantee. Both hooks
     * are plain {@code @Inject}s, applied before this runs (MixinExtras' late injectors are not, so they cannot be
     * checked here).
     */
    private static void checkOptionalHook(String targetClassName, ClassNode targetClass, String methodName, String hook,
            String absentReason, String unboundConsequence) {
        MethodNode target = null;
        for (MethodNode method : targetClass.methods) {
            if (method.name.equals(methodName)) {
                if (target != null) throw new IllegalStateException("Several " + methodName + " methods in " + targetClassName);
                target = method;
            }
        }
        if (target == null) {
            LOGGER.info("No {} in {} ({}); its hook is not applied.", methodName, targetClassName, absentReason);
            return;
        }
        boolean called = false;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null && !called; insn = insn.getNext()) {
            called = insn instanceof MethodInsnNode call && call.owner.equals(targetClass.name)
                    && (call.name.equals(hook) || call.name.endsWith("$" + hook));
        }
        if (!called) {
            throw new IllegalStateException(methodName + " in " + targetClassName + " does not call " + hook + ": " + unboundConsequence);
        }
        LOGGER.info("Bound {} in {}#{}.", hook, targetClassName, methodName);
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
                            get ? "get" : "set", get ? RAND_VALUE_GET : RAND_VALUE_SET, false));
                    rewritten++;
                }
                insn = next;
            }
        }
        LOGGER.info("Made {} randValue accesses per-thread in {}.", rewritten, targetClassName);
    }
}
