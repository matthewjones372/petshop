package petshop.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.subgraph
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.logger
import io.github.matthewjones372.lark.slf4j.Slf4jLogger
import io.github.matthewjones372.lark.test.story
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import petshop.domain.PetId
import petshop.domain.PetShop

/** `main` binds nothing: lark-slf4j registers itself from the classpath, and this fails if it stops. */
class LoggingSpec {

    // Logback reads an event's MDC lazily and the pairs leave the MDC once the logging call returns, so
    // take it while appending.
    private val appender = object : ListAppender<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            event.prepareForDeferredProcessing()
            super.append(event)
        }
    }

    private val backend = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("petshop")

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
    fun `nothing in main binds a logger, and the classpath still answers`() = story {
        Then("the logger lark writes to is SLF4J's, found on the classpath; StderrLogger would mean the dependency does nothing") {
            logger.get().shouldBeInstanceOf<Slf4jLogger>()
        }
    }

    @Test
    fun `an adoption reaches the backend with the pet on it, not in it`() = story {
        When("Ada adopts Nibbles") { testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(1), "Ada") } }
        val adopted = Then("logback received the adoption") { appender.list.single { it.message.contains("adopted") } }
        And("the pet and the adopter are in its MDC, which is what a pattern and a field search read") {
            adopted.mdcPropertyMap["pet_id"] shouldBe "1"
            adopted.mdcPropertyMap["adopted_by"] shouldBe "Ada"
        }
    }
}
