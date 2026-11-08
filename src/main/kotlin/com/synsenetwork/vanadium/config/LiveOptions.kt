package com.synsenetwork.vanadium.config

import me.shedaniel.autoconfig.ConfigData
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Runtime-only options for `/vanadium set`; worker count requires a restart. */
object LiveOptions {
    private val fields: Map<String, Field> = VanadiumConfig::class.java.declaredFields
        .filter {
            !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) &&
                    (it.type == java.lang.Boolean.TYPE || it.type == Integer.TYPE && it.name != "workers")
        }
        .associateBy { it.name }

    val names: Set<String> get() = fields.keys

    fun isBoolean(name: String): Boolean = fields[name]?.type == java.lang.Boolean.TYPE

    fun get(config: VanadiumConfig, name: String): Any? = fields[name]?.get(config)

    /** Null on success; restores the previous value and returns the reason on validation failure. */
    fun set(config: VanadiumConfig, name: String, text: String): String? {
        val field = fields[name] ?: return "unknown option '$name'; live options: ${names.joinToString()}"
        val value: Any = when (field.type) {
            java.lang.Boolean.TYPE -> when (text.lowercase()) {
                "true", "on" -> true
                "false", "off" -> false
                else -> return "$name takes true/false or on/off"
            }
            else -> text.toIntOrNull() ?: return "$name takes an integer"
        }
        val previous = field.get(config)
        field.set(config, value)
        return try {
            config.validatePostLoad()
            null
        } catch (e: ConfigData.ValidationException) {
            field.set(config, previous)
            e.message ?: "invalid value for $name"
        }
    }
}
