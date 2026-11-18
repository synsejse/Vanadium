package com.synsenetwork.vanadium.live

import java.io.Writer
import java.util.Locale
import net.minecraft.server.MinecraftServer

/** Appends [text] and a newline. Writer errors throw, unlike `PrintWriter`'s. */
internal fun Writer.line(text: String): Writer = append(text).append('\n')

/** A fixture's CSV cell or log value: blank for null, a double to three decimals in the root locale, anything else as text. */
internal fun formatValue(value: Any?): String = when (value) {
    null -> ""
    is Double -> "%.3f".format(Locale.ROOT, value)
    else -> value.toString()
}

/** Runs [command] as the server's own command source. Command errors throw; the result is the command's value (false gamerules return 0). */
internal fun MinecraftServer.runCommand(command: String): Int = commands.dispatcher.execute(command, createCommandSourceStack())
