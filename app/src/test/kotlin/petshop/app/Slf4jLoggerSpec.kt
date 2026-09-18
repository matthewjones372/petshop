package petshop.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logError
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logger
import io.github.matthewjones372.lark.parMap
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * What the service's own lines look like once they go where every other line already goes.
 *
 * The appender is logback's, attached to the name the adapter logs under, because a claim about a
 * backend that is asserted against a fake backend is a claim about the fake.
 */
class Slf4jLoggerSpec {

    private val appender = ListAppender<ILoggingEvent>()

    private val backend = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("petshop")

    @BeforeEach
    fun attach() {
        appender.start()
        backend.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        backend.detachAppender(appender)
        appender.stop()
    }

    private fun <A> logging(block: () -> A): A = logger.locally(Slf4jLogger()) { block() }

    @Test
    fun `a line lark wrote arrives at the backend at the level it was written`() {
        logging { logInfo("one tortoise") }

        appender.list.single().let { event ->
            event.level shouldBe Level.INFO
            withClue("the message as written, with nothing appended to it") {
                event.message shouldBe "one tortoise"
            }
        }
    }

    @Test
    fun `a cause is a throwable to the backend, not a line of text`() {
        val boom = IllegalStateException("no tortoises")

        logging { logError("adoption failed", boom) }

        appender.list.single().throwableProxy.message shouldBe "no tortoises"
    }

    @Test
    fun `an annotation is in the MDC while its block runs, and gone afterwards`() {
        logging {
            logAnnotated("pet_id" to "tortoise-1") { logInfo("inside") }
            logInfo("outside")
        }

        val (inside, outside) = appender.list

        inside.mdcPropertyMap["pet_id"] shouldBe "tortoise-1"
        withClue("a thread that logs outside the block must not inherit the last one's key") {
            outside.mdcPropertyMap["pet_id"].shouldBeNull()
        }
    }

    @Test
    fun `a line written on a fork carries the annotation its opener bound`() {
        logging {
            logAnnotated("pet_id" to "tortoise-1") {
                parMap(listOf(1, 2)) { logInfo("fork $it") }
            }
        }

        withClue("this is the claim an MDC cannot make on its own, and the reason for the adapter") {
            appender.list.map { it.mdcPropertyMap["pet_id"] } shouldContainExactly
                listOf("tortoise-1", "tortoise-1")
        }
    }
}
