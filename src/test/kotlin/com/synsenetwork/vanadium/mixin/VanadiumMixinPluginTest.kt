package com.synsenetwork.vanadium.mixin

import com.google.gson.JsonParser
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [VanadiumMixinPlugin] matches mixins, hooks and the per-thread `randValue` holder by string, so a rename or move
 * that misses one silently drops the synchronize bits, the `randValue` rewrite or a hook check.
 */
class VanadiumMixinPluginTest {
    private val config = javaClass.classLoader.getResourceAsStream("vanadium.mixins.json")!!.reader().use {
        JsonParser.parseReader(it).asJsonObject
    }

    @Test
    fun pluginIsRegistered() {
        assertEquals(VanadiumMixinPlugin::class.java.name, config["plugin"].asString)
    }

    @Test
    fun mixinNamesAreRegistered() {
        val pkg = config["package"].asString
        val registered = config.getAsJsonArray("mixins").map { "$pkg.${it.asString}" }.toSet()
        for (name in listOf(VanadiumMixinPlugin.SYNC_ALL, VanadiumMixinPlugin.RAND_VALUE, VanadiumMixinPlugin.POI_TAKE_AT,
                VanadiumMixinPlugin.POI_UNLOAD)) {
            assertTrue(name in registered, "$name is not in vanadium.mixins.json")
            read(name.replace('.', '/'))
        }
    }

    @Test
    fun optionalHooksExist() {
        val hooks = mapOf(VanadiumMixinPlugin.POI_TAKE_AT to VanadiumMixinPlugin.TAKE_AT_HOOK,
            VanadiumMixinPlugin.POI_UNLOAD to VanadiumMixinPlugin.SHOULD_UNLOAD_POI_HOOK)
        for ((mixin, hook) in hooks) {
            assertTrue(read(mixin.replace('.', '/')).methods.any { it.name == hook }, "$mixin has no $hook")
        }
    }

    @Test
    fun randValueStateHasStaticAccessors() {
        val state = read(VanadiumMixinPlugin.RAND_VALUE_STATE)
        for ((name, desc) in listOf("get" to VanadiumMixinPlugin.RAND_VALUE_GET, "set" to VanadiumMixinPlugin.RAND_VALUE_SET)) {
            assertTrue(state.methods.any { it.name == name && it.desc == desc && it.access and Opcodes.ACC_STATIC != 0 },
                "${state.name} has no static $name$desc")
        }
    }

    @Test
    fun unsynchronizedMethodsBelongToSyncAllTargets() {
        val mixin = read(VanadiumMixinPlugin.SYNC_ALL.replace('.', '/'))
        val annotation = mixin.invisibleAnnotations.orEmpty().first { it.desc.endsWith("/Mixin;") }
        val targets = (annotation.values.chunked(2).first { it[0] == "value" }[1] as List<*>).map { (it as Type).className }
        for (entry in VanadiumMixinPlugin.UNSYNCHRONIZED) {
            val owner = entry.substringBefore('#')
            val method = entry.substringAfter('#')
            assertTrue(owner in targets, "$owner is not a SyncAllMixin target")
            assertTrue(read(owner.replace('.', '/')).methods.any { it.name == method }, "$owner has no $method")
        }
    }

    private fun read(name: String): ClassNode {
        val stream = javaClass.classLoader.getResourceAsStream("$name.class") ?: throw IOException("Missing class $name")
        return stream.use { ClassNode().also { node -> ClassReader(it).accept(node, ClassReader.SKIP_CODE) } }
    }
}
