package petshop.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.logger
import io.github.matthewjones372.lark.slf4j.Slf4jLogger
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import petshop.domain.PetId
import petshop.domain.PetShop

/**
 * That this service's own lines end up where its libraries' lines already do, with the pairs still
 * on them.
 *
 * `main` binds nothing. `lark-slf4j` is on the classpath and registers itself, and this is the test
 * that says so — a dependency that quietly stopped being one would otherwise look like silence.
 */
class LoggingSpec {

    private val appender = ListAppender<ILoggingEvent>()

    private val backend = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("lark")

    private val settled: Module = shopWith(FakeRegistry())

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

    @Test
    fun `nothing in main binds a logger, and the classpath still answers`() {
        withClue("if this is StderrLogger the dependency is no longer doing anything") {
            logger.get().shouldBeInstanceOf<Slf4jLogger>()
        }
    }

    @Test
    fun `an adoption reaches the backend with the pet on it, not in it`() {
        testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(1), "Ada"); Unit }

        val adopted = appender.list.single { it.message.contains("adopted") }

        withClue("in the MDC, which is what a pattern and a field search read") {
            adopted.mdcPropertyMap["pet_id"] shouldBe "1"
        }
        adopted.mdcPropertyMap["adopted_by"] shouldBe "Ada"
    }
}
