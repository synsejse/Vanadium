package com.synsenetwork.vanadium.config;

import static org.junit.jupiter.api.Assertions.*;

import com.synsenetwork.vanadium.config.ConfigOptions.BoolOption;
import com.synsenetwork.vanadium.config.ConfigOptions.IntOption;
import com.synsenetwork.vanadium.config.ConfigOptions.Option;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigOptionsTest {
    @Test void everyPublicFieldBecomesAnOption() {
        Set<String> fieldNames = new HashSet<>();
        for (Field field : VanadiumConfig.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
                fieldNames.add(field.getName());
            }
        }
        Set<String> optionNames = new HashSet<>();
        for (Option option : ConfigOptions.ALL) {
            optionNames.add(option.name());
        }
        assertEquals(fieldNames, optionNames);
        assertFalse(optionNames.isEmpty());
    }

    @Test void boolOptionsRoundTrip() {
        VanadiumConfig config = new VanadiumConfig();
        for (Option option : ConfigOptions.ALL) {
            if (option instanceof BoolOption bool) {
                bool.set().accept(config, false);
                assertFalse(bool.get().apply(config), option.name() + " set(false) not read back");
                bool.set().accept(config, true);
                assertTrue(bool.get().apply(config), option.name() + " set(true) not read back");
            }
        }
    }

    @Test void intOptionsRoundTrip() {
        VanadiumConfig config = new VanadiumConfig();
        for (Option option : ConfigOptions.ALL) {
            if (option instanceof IntOption anInt) {
                anInt.set().accept(config, 42);
                assertEquals(42, anInt.get().applyAsInt(config), option.name() + " set(42) not read back");
            }
        }
    }

    @Test void onlyWorkersRequiresRestart() {
        for (Option option : ConfigOptions.ALL) {
            assertEquals(!option.name().equals("workers"), option.live(), option.name());
        }
    }

    @Test void minBoundsFollowAnnotations() {
        for (Option option : ConfigOptions.ALL) {
            if (option instanceof IntOption anInt) {
                int expected = anInt.name().equals("cellSize") ? 0 : Integer.MIN_VALUE;
                assertEquals(expected, anInt.min(), option.name());
            }
        }
    }

    @Test void resetRestoresDefaults() {
        VanadiumConfig config = new VanadiumConfig();
        VanadiumConfig defaults = new VanadiumConfig();
        for (Option option : ConfigOptions.ALL) {
            switch (option) {
                case BoolOption bool -> bool.set().accept(config, !bool.def());
                case IntOption anInt -> anInt.set().accept(config, anInt.def() + 99);
            }
        }
        ConfigOptions.resetToDefaults(config);
        for (Option option : ConfigOptions.ALL) {
            switch (option) {
                case BoolOption bool -> assertEquals(bool.get().apply(defaults), bool.get().apply(config), option.name());
                case IntOption anInt -> assertEquals(anInt.get().applyAsInt(defaults), anInt.get().applyAsInt(config), option.name());
            }
        }
    }
}
