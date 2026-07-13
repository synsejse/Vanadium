package com.synsenetwork.vanadium.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflective view over {@link VanadiumConfig}'s public instance fields — the single source of
 * truth for every user-facing option. The /vanadium command tree, tab completion, status
 * output, and defaults reset all iterate {@link #fields()} on demand: adding a field to
 * VanadiumConfig is all it takes to expose a new option. Field initializers supply defaults,
 * {@link RestartRequired} marks restart-only options, and {@link Min} bounds int options.
 *
 * <p>Purely mechanical: the callers decide how to present each field from its {@code getType()}.
 */
public final class ConfigOptions {
    private ConfigOptions() {
    }

    /** The public instance fields of {@link VanadiumConfig}, in declaration order. */
    public static List<Field> fields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : VanadiumConfig.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** {@code false} → the field's changes only take effect on restart. */
    public static boolean isLive(Field field) {
        return !field.isAnnotationPresent(RestartRequired.class);
    }

    /** Lower bound for an int field, or {@link Integer#MIN_VALUE} if unannotated. */
    public static int min(Field field) {
        Min min = field.getAnnotation(Min.class);
        return min != null ? min.value() : Integer.MIN_VALUE;
    }

    public static boolean getBool(Field field, VanadiumConfig config) {
        try {
            return field.getBoolean(config);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e); // fields are public
        }
    }

    public static void setBool(Field field, VanadiumConfig config, boolean value) {
        try {
            field.setBoolean(config, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    public static int getInt(Field field, VanadiumConfig config) {
        try {
            return field.getInt(config);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    public static void setInt(Field field, VanadiumConfig config, int value) {
        try {
            field.setInt(config, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /** Copies every option's default value (from a fresh config) onto the given config. */
    public static void resetToDefaults(VanadiumConfig config) {
        VanadiumConfig defaults = new VanadiumConfig();
        for (Field field : fields()) {
            try {
                field.set(config, field.get(defaults));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }
}
