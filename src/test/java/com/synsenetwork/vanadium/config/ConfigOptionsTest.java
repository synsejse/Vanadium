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

    @Test void findResolvesEveryNameAndRejectsUnknown() {
        for (Option option : ConfigOptions.ALL) {
            assertTrue(ConfigOptions.find(option.name()).isPresent(), option.name());
        }
        assertTrue(ConfigOptions.find("nope").isEmpty());
    }

    @Test void namesAreUnique() {
        Set<String> names = new HashSet<>();
        for (Option option : ConfigOptions.ALL) {
            assertTrue(names.add(option.name()), "duplicate option name: " + option.name());
        }
    }

    @Test void resetRestoresDefaults() {
        VanadiumConfig config = new VanadiumConfig();
        VanadiumConfig defaults = new VanadiumConfig();
        config.enabled = false;
        config.workers = 99;
        config.cellSize = 7;
        config.parallelEntities = false;
        config.parallelBlockEntities = false;
        config.parallelChunkTicks = false;
        config.chunkCache = false;
        config.parallelChunkLoads = false;
        ConfigOptions.resetToDefaults(config);
        assertEquals(defaults.enabled, config.enabled);
        assertEquals(defaults.workers, config.workers);
        assertEquals(defaults.cellSize, config.cellSize);
        assertEquals(defaults.parallelEntities, config.parallelEntities);
        assertEquals(defaults.parallelBlockEntities, config.parallelBlockEntities);
        assertEquals(defaults.parallelChunkTicks, config.parallelChunkTicks);
        assertEquals(defaults.chunkCache, config.chunkCache);
        assertEquals(defaults.parallelChunkLoads, config.parallelChunkLoads);
    }

    /** Keeps registry and POJO in sync: every public instance field has exactly one same-named option. */
    @Test void everyConfigFieldHasExactlyOneOption() {
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
        assertEquals(fieldNames, optionNames, "config fields and registry options out of sync");
    }
}
