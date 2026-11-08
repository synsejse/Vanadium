package com.synsenetwork.vanadium.config

import net.minecraft.resources.Identifier

/** Exact registry-ID rules, compiled from the configured list only when the list changes. */
class TypeRules {
    private var source: List<String> = emptyList()
    private var ids: Set<Identifier> = emptySet()

    operator fun contains(id: Identifier): Boolean = id in ids

    /** Lets per-ticker callers skip resolving registry IDs when no rule is configured. */
    fun isEmpty(): Boolean = ids.isEmpty()

    fun update(rules: List<String>) {
        if (source != rules) {
            ids = parse(rules)
            source = rules.toList()
        }
    }

    companion object {
        /** Validates `namespace:type` IDs; throws [IllegalArgumentException] for malformed entries. */
        @JvmStatic
        fun parse(rules: List<String?>?): Set<Identifier> {
            requireNotNull(rules) { "Type rules must be a list" }
            return rules.mapTo(HashSet()) { name ->
                requireNotNull(name) { "Type IDs must not be null" }
                val id = Identifier.tryParse(name)
                require(':' in name && id != null && !name.startsWith(':') && !name.endsWith(':')) {
                    "Expected namespace:type ID, got: $name"
                }
                id
            }
        }
    }
}
