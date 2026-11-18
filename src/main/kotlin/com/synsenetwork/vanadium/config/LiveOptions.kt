package com.synsenetwork.vanadium.config

import com.synsenetwork.vanadium.tick.IdleWorkers
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import me.shedaniel.autoconfig.ConfigData

/** Runtime-only options for `/vanadium set`: booleans, integers and choices; worker count requires a restart. */
object LiveOptions {
    /** Text options `set` accepts, with their values. A text field not listed here is not a live option. */
    private val CHOICES: Map<String, List<String>> = mapOf("idleWorkers" to IdleWorkers.names)

    /** Fields that never change live although their type would allow it. */
    private val RESTART_ONLY = setOf("workers")

    private val fields: Map<String, Field> = liveFields(VanadiumConfig::class.java, CHOICES.keys)

    val names: Set<String> get() = fields.keys

    fun isBoolean(name: String): Boolean = fields[name]?.type == java.lang.Boolean.TYPE

    /** The accepted values of a text option, for suggestions; null for any other option. */
    fun choices(name: String): List<String>? = if (name in fields) CHOICES[name] else null

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
            String::class.java -> {
                val choices = CHOICES.getValue(name)
                choices.firstOrNull { it.equals(text, ignoreCase = true) }
                    ?: return "$name takes one of ${choices.joinToString()}"
            }
            Integer.TYPE -> text.toIntOrNull() ?: return "$name takes an integer"
            else -> error("Unsupported live option type: ${field.type.name}")
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

    /**
     * The live fields of [type], in declaration order: every boolean and integer field except [RESTART_ONLY], and
     * the text fields named in [choices]. Static and transient fields, other types and other text never are.
     */
    internal fun liveFields(type: Class<*>, choices: Set<String>): Map<String, Field> = type.declaredFields
        .filter { field ->
            !Modifier.isStatic(field.modifiers) && !Modifier.isTransient(field.modifiers) && field.name !in RESTART_ONLY &&
                when (field.type) {
                    java.lang.Boolean.TYPE, Integer.TYPE -> true
                    String::class.java -> field.name in choices
                    else -> false
                }
        }
        .associateBy { it.name }
}
