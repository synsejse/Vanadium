package com.synsenetwork.vanadium.compat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks vanilla member bindings without initializing Minecraft or applying mixins. */
class MixinTargetsTest {
    @TestFactory
    Stream<DynamicTest> vanillaBindings() throws IOException {
        var loader = getClass().getClassLoader();
        var config = JsonParser.parseReader(new InputStreamReader(
                loader.getResourceAsStream("vanadium.mixins.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        List<DynamicTest> tests = new ArrayList<>();
        for (var name : config.getAsJsonArray("mixins")) {
            String mixinName = config.get("package").getAsString() + "." + name.getAsString();
            ClassNode mixin = read(mixinName.replace('.', '/'));
            AnnotationNode annotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                    .filter(a -> a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
            for (Object targetType : (List<?>) value(annotation, "value")) {
                ClassNode target = read(((Type) targetType).getInternalName());
                tests.add(DynamicTest.dynamicTest(mixinName + " -> " + target.name, () -> check(mixin, target)));
            }
        }
        return tests.stream();
    }

    private void check(ClassNode mixin, ClassNode target) throws IOException {
        List<String> missing = new ArrayList<>();
        if (!hasSuperclass(target, mixin.superName)) missing.add("superclass " + mixin.superName);
        for (var field : mixin.fields) {
            if (annotations(field.visibleAnnotations, field.invisibleAnnotations).stream().anyMatch(a -> a.desc.endsWith("/Shadow;"))
                    && !hasField(target, field.name, field.desc)) {
                missing.add("field " + field.name + " " + field.desc);
            }
        }
        for (var method : mixin.methods) {
            for (var annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (annotation.desc.endsWith("/Shadow;") && !hasMethod(target, method.name, method.desc)) {
                    missing.add("shadow " + method.name + method.desc);
                }
                if (annotation.desc.endsWith("/Accessor;")) {
                    String descriptor = Type.getReturnType(method.desc).getDescriptor();
                    if (descriptor.equals("V")) descriptor = Type.getArgumentTypes(method.desc)[0].getDescriptor();
                    if (!hasField(target, (String) value(annotation, "value"), descriptor)) {
                        missing.add("accessor " + value(annotation, "value") + " " + descriptor);
                    }
                }
                if (annotation.desc.endsWith("/Invoker;") && !hasMethod(target, (String) value(annotation, "value"), method.desc)) {
                    missing.add("invoker " + value(annotation, "value") + method.desc);
                }
                Object selectors = value(annotation, "method");
                if (selectors instanceof List<?> list) {
                    for (Object selector : list) {
                        String text = selector.toString();
                        if (text.contains("*")) continue;
                        int descriptor = text.indexOf('(');
                        String name = descriptor < 0 ? text : text.substring(0, descriptor);
                        String desc = descriptor < 0 ? null : text.substring(descriptor);
                        if (target.methods.stream().noneMatch(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc)))) {
                            missing.add("injection " + text);
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> String.join("\n", missing));
    }

    private boolean hasSuperclass(ClassNode target, String name) throws IOException {
        return target.name.equals(name) || target.superName != null && hasSuperclass(read(target.superName), name);
    }

    private boolean hasField(ClassNode target, String name, String descriptor) throws IOException {
        return target.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor))
                || target.superName != null && hasField(read(target.superName), name, descriptor);
    }

    private boolean hasMethod(ClassNode target, String name, String descriptor) throws IOException {
        if (target.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor))) return true;
        if (target.superName != null && hasMethod(read(target.superName), name, descriptor)) return true;
        for (String parent : target.interfaces) if (hasMethod(read(parent), name, descriptor)) return true;
        return false;
    }

    private ClassNode read(String name) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new IOException("Missing class " + name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> result = new ArrayList<>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }
}
