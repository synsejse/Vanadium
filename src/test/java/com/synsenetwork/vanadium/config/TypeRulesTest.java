package com.synsenetwork.vanadium.config;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypeRulesTest {
    @Test void exactIdsAreDeduplicated() {
        var rules = TypeRules.parse(List.of("minecraft:pig", "example:machine", "minecraft:pig"));
        assertEquals(2, rules.size());
        assertTrue(rules.contains(Identifier.of("example:machine")));
        assertFalse(rules.contains(Identifier.of("example:other")));
    }

    @Test void emptyRulesClearTheCache() {
        TypeRules rules = new TypeRules();
        Identifier pig = Identifier.of("minecraft:pig");
        rules.update(List.of("minecraft:pig"));
        assertTrue(rules.contains(pig));
        rules.update(List.of());
        assertFalse(rules.contains(pig));
        rules.update(List.of("minecraft:pig"));
        assertTrue(rules.contains(pig));
        rules.update(List.of());
        assertFalse(rules.contains(pig));
    }

    @Test void malformedRulesAreRejectedWithoutChangingConfig() throws Exception {
        var config = new VanadiumConfig();
        var field = VanadiumConfig.class.getField("serialEntityTypes");
        ConfigOptions.setList(field, config, List.of("minecraft:pig"));
        for (String invalid : new String[]{"pig", "minecraft:*", "Minecraft:pig", "minecraft:pig,", ":pig", "minecraft:"}) {
            assertThrows(IllegalArgumentException.class, () -> ConfigOptions.setList(field, config, List.of(invalid)));
            assertEquals(List.of("minecraft:pig"), config.serialEntityTypes);
        }
    }

    @Test void resetAndReloadedTextInvalidateCompiledRules() {
        var config = new VanadiumConfig();
        var pig = Identifier.of("minecraft:pig");
        config.serialEntityTypes.add("minecraft:pig");
        config.refreshSerialRules();
        assertTrue(config.isSerialEntity(pig));
        assertFalse(config.isSerialBlockEntity(pig));
        ConfigOptions.resetToDefaults(config);
        config.refreshSerialRules();
        assertFalse(config.isSerialEntity(pig));
        config.serialBlockEntityTypes.add("minecraft:pig");
        config.refreshSerialRules();
        assertTrue(config.isSerialBlockEntity(pig));
    }
}
