package com.synsenetwork.vanadium.integration

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property

/**
 * Records, in [seen], the label [classify] gives a logged message (null skips it), in order with the [mark]s logged
 * through the same configuration: a late asynchronous event still lands before the mark logged after it.
 */
internal class LogCapture(private val classify: (String) -> String?) :
    AbstractAppender("VanadiumLogCapture", null, null, true, Property.EMPTY_ARRAY) {
    private val context = LogManager.getContext(false) as LoggerContext
    val seen = ConcurrentLinkedQueue<String>()

    init {
        start()
        context.configuration.rootLogger.addAppender(this, null, null)
        context.updateLoggers()
    }

    fun mark(name: String) = LOGGER.info("{}{}", MARK, name)

    /** Whether mark [name] has arrived. */
    fun marked(name: String): Boolean = "$MARK$name" in seen

    /** Waits up to five seconds for mark [name], then returns what came before it. */
    fun until(name: String): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!marked(name)) {
            check(System.nanoTime() < deadline) { "log mark '$name' never arrived" }
            Thread.onSpinWait()
        }
        return seen.toList().takeWhile { it != "$MARK$name" }
    }

    override fun append(event: LogEvent) {
        val message = event.message.formattedMessage
        if (message.startsWith(MARK)) seen.add(message) else classify(message)?.let(seen::add)
    }

    fun close() {
        context.configuration.rootLogger.removeAppender(name)
        context.updateLoggers()
        stop()
    }

    companion object {
        private val LOGGER = LogManager.getLogger("VanadiumLogCapture")
        const val MARK = "VANADIUM_LOG_MARK "
    }
}
