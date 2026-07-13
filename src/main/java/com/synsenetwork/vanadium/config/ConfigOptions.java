package com.synsenetwork.vanadium.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/**
 * The user-facing config options, derived reflectively from {@link VanadiumConfig}'s public
 * instance fields — the POJO is the single source of truth. The /vanadium command tree, tab
 * completion, status output, and defaults reset are all generated from {@link #ALL}: adding a
 * field to VanadiumConfig is all it takes to expose a new option. Defaults come from the field
 * initializers, {@link RestartRequired} marks restart-only options, and {@link Min} bounds the
 * accepted values of int options.
 */
public final class ConfigOptions {
    /** One user-facing option. {@code live() == false} → changes only take effect on restart. */
    public sealed interface Option permits BoolOption, IntOption {
        String name();

        boolean live();
    }

    public record BoolOption(String name, boolean live,
                             Function<VanadiumConfig, Boolean> get,
                             BiConsumer<VanadiumConfig, Boolean> set,
                             boolean def) implements Option {
    }

    public record IntOption(String name, boolean live,
                            ToIntFunction<VanadiumConfig> get,
                            ObjIntConsumer<VanadiumConfig> set,
                            int def, int min) implements Option {
    }

    public static final List<Option> ALL = build();

    private ConfigOptions() {
    }

    private static List<Option> build() {
        VanadiumConfig defaults = new VanadiumConfig();
        List<Option> options = new ArrayList<>();
        for (Field field : VanadiumConfig.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = field.getName();
            boolean live = !field.isAnnotationPresent(RestartRequired.class);
            if (field.getType() == boolean.class) {
                options.add(new BoolOption(name, live,
                        config -> getBool(field, config), (config, value) -> setBool(field, config, value),
                        getBool(field, defaults)));
            } else if (field.getType() == int.class) {
                Min min = field.getAnnotation(Min.class);
                options.add(new IntOption(name, live,
                        config -> getInt(field, config), (config, value) -> setInt(field, config, value),
                        getInt(field, defaults), min != null ? min.value() : Integer.MIN_VALUE));
            } else {
                throw new IllegalStateException("Unsupported config field type: " + field);
            }
        }
        return List.copyOf(options);
    }

    /** Resets every option on the given config to its default value. */
    public static void resetToDefaults(VanadiumConfig config) {
        for (Option option : ALL) {
            switch (option) {
                case BoolOption bool -> bool.set().accept(config, bool.def());
                case IntOption anInt -> anInt.set().accept(config, anInt.def());
            }
        }
    }

    private static boolean getBool(Field field, VanadiumConfig config) {
        try {
            return field.getBoolean(config);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e); // fields are public
        }
    }

    private static void setBool(Field field, VanadiumConfig config, boolean value) {
        try {
            field.setBoolean(config, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static int getInt(Field field, VanadiumConfig config) {
        try {
            return field.getInt(config);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static void setInt(Field field, VanadiumConfig config, int value) {
        try {
            field.setInt(config, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }
}
