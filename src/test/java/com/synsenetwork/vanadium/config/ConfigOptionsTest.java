package com.synsenetwork.vanadium.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConfigOptionsTest {
    @Test void fieldsAreEveryPublicInstanceField() {
        long expected = Arrays.stream(VanadiumConfig.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers()))
                .count();
        assertEquals(expected, ConfigOptions.fields().size());
        assertFalse(ConfigOptions.fields().isEmpty());
    }

    @Test void everyFieldIsBoolOrInt() {
        for (Field field : ConfigOptions.fields()) {
            assertTrue(field.getType() == boolean.class || field.getType() == int.class, field.getName());
        }
    }

    @Test void boolAccessorsRoundTrip() {
        VanadiumConfig config = new VanadiumConfig();
        for (Field field : ConfigOptions.fields()) {
            if (field.getType() == boolean.class) {
                ConfigOptions.setBool(field, config, false);
                assertFalse(ConfigOptions.getBool(field, config), field.getName());
                ConfigOptions.setBool(field, config, true);
                assertTrue(ConfigOptions.getBool(field, config), field.getName());
            }
        }
    }

    @Test void intAccessorsRoundTrip() {
        VanadiumConfig config = new VanadiumConfig();
        for (Field field : ConfigOptions.fields()) {
            if (field.getType() == int.class) {
                ConfigOptions.setInt(field, config, 42);
                assertEquals(42, ConfigOptions.getInt(field, config), field.getName());
            }
        }
    }

    @Test void onlyWorkersRequiresRestart() {
        for (Field field : ConfigOptions.fields()) {
            assertEquals(!field.getName().equals("workers"), ConfigOptions.isLive(field), field.getName());
        }
    }

    @Test void minBoundsFollowAnnotations() {
        for (Field field : ConfigOptions.fields()) {
            if (field.getType() == int.class) {
                int expected = field.getName().equals("cellSize") ? 0 : Integer.MIN_VALUE;
                assertEquals(expected, ConfigOptions.min(field), field.getName());
            }
        }
    }

    @Test void resetRestoresDefaults() {
        VanadiumConfig config = new VanadiumConfig();
        VanadiumConfig defaults = new VanadiumConfig();
        for (Field field : ConfigOptions.fields()) {
            if (field.getType() == boolean.class) {
                ConfigOptions.setBool(field, config, !ConfigOptions.getBool(field, defaults));
            } else {
                ConfigOptions.setInt(field, config, ConfigOptions.getInt(field, defaults) + 99);
            }
        }
        ConfigOptions.resetToDefaults(config);
        for (Field field : ConfigOptions.fields()) {
            if (field.getType() == boolean.class) {
                assertEquals(ConfigOptions.getBool(field, defaults), ConfigOptions.getBool(field, config), field.getName());
            } else {
                assertEquals(ConfigOptions.getInt(field, defaults), ConfigOptions.getInt(field, config), field.getName());
            }
        }
    }
}
