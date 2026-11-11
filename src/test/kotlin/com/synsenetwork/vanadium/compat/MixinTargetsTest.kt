package com.synsenetwork.vanadium.compat

import com.google.gson.JsonParser
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.io.IOException
import kotlin.test.assertTrue

/**
 * Checks member bindings without initializing Minecraft or applying mixins, against vanilla and the pinned
 * Lithium jar on the test classpath. Injections may target methods Lithium's own mixins merge into a class.
 */
class MixinTargetsTest {
    @TestFactory
    fun vanillaBindings(): List<DynamicTest> {
        val config = javaClass.classLoader.getResourceAsStream("vanadium.mixins.json")!!.reader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val pkg = config["package"].asString
        return config.getAsJsonArray("mixins").flatMap { name ->
            val mixinName = "$pkg.${name.asString}"
            val mixin = read(mixinName.replace('.', '/'))
            val annotation = mixin.annotations().first { it.desc.endsWith("/Mixin;") }
            val targets = (annotation["value"] as List<*>?).orEmpty().map { (it as Type).internalName } +
                    (annotation["targets"] as List<*>?).orEmpty().map { (it as String).replace('.', '/') }
            check(targets.isNotEmpty()) { "No mixin targets: $mixinName" }
            targets.map { targetName ->
                val target = read(targetName)
                DynamicTest.dynamicTest("$mixinName -> ${target.name}") { check(mixin, target) }
            }
        }
    }

    private fun check(mixin: ClassNode, target: ClassNode) {
        val missing = ArrayList<String>()
        if (!hasSuperclass(target, mixin.superName)) missing.add("superclass ${mixin.superName}")
        for (field in mixin.fields) {
            if (annotations(field.visibleAnnotations, field.invisibleAnnotations).any { it.desc.endsWith("/Shadow;") } &&
                    !hasField(target, field.name, field.desc)) missing.add("field ${field.name} ${field.desc}")
        }
        for (method in mixin.methods) {
            for (annotation in annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                val kind = annotation.desc.substringAfterLast('/').removeSuffix(";")
                when (kind) {
                    "Shadow", "Overwrite" -> if (!hasMethod(target, method.name, method.desc)) {
                        missing.add("${kind.lowercase()} ${method.name}${method.desc}")
                    }
                    "Accessor" -> {
                        var descriptor = Type.getReturnType(method.desc).descriptor
                        if (descriptor == "V") descriptor = Type.getArgumentTypes(method.desc)[0].descriptor
                        if (!hasField(target, annotation["value"] as String, descriptor)) missing.add("accessor ${annotation["value"]} $descriptor")
                    }
                    "Invoker" -> if (!hasMethod(target, annotation["value"] as String, method.desc)) {
                        missing.add("invoker ${annotation["value"]}${method.desc}")
                    }
                }
                for (selector in (annotation["method"] as List<*>?).orEmpty().map(Any?::toString)) {
                    if ('*' in selector) continue
                    val name = selector.substringBefore('(')
                    val desc = if ('(' in selector) "(" + selector.substringAfter('(') else null
                    val merged = lithiumMerged[target.name].orEmpty()
                    if (target.methods.none { it.name == name && (desc == null || it.desc == desc) } &&
                            merged.none { it.first == name && (desc == null || it.second == desc) }) missing.add("injection $selector")
                }
            }
        }
        assertTrue(missing.isEmpty(), missing.joinToString("\n"))
    }

    /** Methods each Lithium mixin class adds to its targets, by target internal name. */
    private val lithiumMerged: Map<String, List<Pair<String, String>>> by lazy {
        val config = javaClass.classLoader.getResourceAsStream("lithium.mixins.json")!!.reader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val pkg = config["package"].asString
        val merged = HashMap<String, MutableList<Pair<String, String>>>()
        for (name in config.getAsJsonArray("mixins")) {
            val mixin = read("$pkg.${name.asString}".replace('.', '/'))
            val annotation = mixin.annotations().firstOrNull { it.desc.endsWith("/Mixin;") } ?: continue
            val targets = (annotation["value"] as List<*>?).orEmpty().map { (it as Type).internalName } +
                    (annotation["targets"] as List<*>?).orEmpty().map { (it as String).replace('.', '/') }
            val methods = mixin.methods.map { it.name to it.desc }
            for (target in targets) merged.getOrPut(target) { ArrayList() }.addAll(methods)
        }
        merged
    }

    private fun hasSuperclass(target: ClassNode, name: String): Boolean =
        target.name == name || target.superName != null && hasSuperclass(read(target.superName), name)

    private fun hasField(target: ClassNode, name: String, descriptor: String): Boolean =
        target.fields.any { it.name == name && it.desc == descriptor } ||
                target.superName != null && hasField(read(target.superName), name, descriptor)

    private fun hasMethod(target: ClassNode, name: String, descriptor: String): Boolean =
        target.methods.any { it.name == name && it.desc == descriptor } ||
                target.superName != null && hasMethod(read(target.superName), name, descriptor) ||
                target.interfaces.any { hasMethod(read(it), name, descriptor) }

    private fun read(name: String): ClassNode {
        val stream = javaClass.classLoader.getResourceAsStream("$name.class") ?: throw IOException("Missing class $name")
        return stream.use { ClassNode().also { node -> ClassReader(it).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) } }
    }

    private operator fun AnnotationNode.get(key: String): Any? =
        values?.chunked(2)?.firstOrNull { it[0] == key }?.get(1)

    private fun ClassNode.annotations() = annotations(visibleAnnotations, invisibleAnnotations)

    private fun annotations(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?): List<AnnotationNode> =
        visible.orEmpty() + invisible.orEmpty()
}
