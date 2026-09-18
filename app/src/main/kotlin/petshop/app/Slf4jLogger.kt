package petshop.app

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * lark's own lines, handed to the slf4j backend the rest of the service already logs through.
 *
 * The annotations go into the MDC rather than onto the end of the message. A pair bound by
 * `logAnnotated` is carried across a fork, which an MDC cannot do by itself, and appending it to the
 * text would leave `%X{pet_id}`, a JSON encoder and every field search with nothing to find.
 */
class Slf4jLogger(name: String = "petshop") : Logger {

    private val log = LoggerFactory.getLogger(name)

    override fun log(line: LogLine) {
        // Put back rather than cleared: a key the service set outside lark is not lark's to drop,
        // and the thread this runs on is one a pool may hand to something else next.
        val before = MDC.getCopyOfContextMap()
        line.annotations.forEach { (key, value) -> MDC.put(key, value) }
        try {
            say(line)
        } finally {
            if (before == null) MDC.clear() else MDC.setContextMap(before)
        }
    }

    private fun say(line: LogLine) = when (line.level) {
        LogLevel.Debug -> log.debug(line.message, line.cause)
        LogLevel.Info -> log.info(line.message, line.cause)
        LogLevel.Warn -> log.warn(line.message, line.cause)
        LogLevel.Error -> log.error(line.message, line.cause)
    }
}
