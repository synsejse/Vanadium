package com.synsenetwork.vanadium.config;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypeRulesTest {
    @Test void exactIdsAreDeduplicated() {
        var rules = TypeRules.parse(List.of("minecraft:pig", "example:machine", "minecraft:pig"));
        assertEquals(2, rules.size());
        assertTrue(rules.contains(Identifier.parse("example:machine")));
        assertFalse(rules.contains(Identifier.parse("example:other")));
    }

    @Test void emptyRulesClearTheCache() {
        TypeRules rules = new TypeRules();
        Identifier pig = Identifier.parse("minecraft:pig");
        rules.update(List.of("minecraft:pig"));
        assertTrue(rules.contains(pig));
        rules.update(List.of());
        assertFalse(rules.contains(pig));
        rules.update(List.of("minecraft:pig"));
        assertTrue(rules.contains(pig));
        rules.update(List.of());
        assertFalse(rules.contains(pig));
    }

    @Test void malformedRulesAreRejected() {
        for (String invalid : new String[]{"pig", "minecraft:*", "Minecraft:pig", "minecraft:pig,", ":pig", "minecraft:"}) {
            assertThrows(IllegalArgumentException.class, () -> TypeRules.parse(List.of(invalid)));
        }
    }

    @Test void listChangesInvalidateCompiledRules() {
        var config = new VanadiumConfig();
        var pig = Identifier.parse("minecraft:pig");
        config.serialEntityTypes.add("minecraft:pig");
        config.refreshSerialRules();
        assertTrue(config.isSerialEntity(pig));
        assertFalse(config.isSerialBlockEntity(pig));
        config.serialEntityTypes.clear();
        config.refreshSerialRules();
        assertFalse(config.isSerialEntity(pig));
        config.serialBlockEntityTypes.add("minecraft:pig");
        config.refreshSerialRules();
        assertTrue(config.isSerialBlockEntity(pig));
    }
}
