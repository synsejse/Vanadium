package com.synsenetwork.vanadium.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Exact registry IDs, compiled only when the configured list changes. Server-thread confined. */
public final class TypeRules {
    private List<String> source = List.of();
    private Set<Identifier> ids = Set.of();

    public boolean contains(Identifier id) {
        return ids.contains(id);
    }

    public void update(List<String> rules) {
        if (!source.equals(rules)) {
            Set<Identifier> parsed = parse(rules);
            source = List.copyOf(rules);
            ids = parsed;
        }
    }

    public static Set<Identifier> parse(List<String> rules) {
        if (rules == null) throw new IllegalArgumentException("Type rules must be a list");
        Set<Identifier> ids = new HashSet<>();
        for (String name : rules) {
            if (name == null) throw new IllegalArgumentException("Type IDs must not be null");
            Identifier id = Identifier.tryParse(name);
            if (!name.contains(":") || id == null || name.startsWith(":") || name.endsWith(":")) {
                throw new IllegalArgumentException("Expected namespace:type ID, got: " + name);
            }
            ids.add(id);
        }
        return Set.copyOf(ids);
    }
}
