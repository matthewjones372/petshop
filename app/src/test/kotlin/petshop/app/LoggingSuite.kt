package petshop.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.logger
import io.github.matthewjones372.lark.slf4j.Slf4jLogger
import org.slf4j.LoggerFactory
import petshop.domain.PetId
import petshop.domain.PetShop
import java.util.concurrent.Executors

/** `main` binds nothing: lark-slf4j registers itself from the classpath, and this fails if it stops. */
object LoggingSuite : Suite() {
    private val backend = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("petshop")

    private val settled: Module = shopWith(FakeRegistry())

    init {
        test("nothing in main binds a logger, and the classpath still answers") {
            story {
                Then(
                    "the logger lark writes to is SLF4J's, found on the classpath; " +
                        "StderrLogger would mean the dependency does nothing",
                ) {
                    asMainDoes { logger.get() }.shouldBeInstanceOf<Slf4jLogger>()
                }
            }
        }

        test("an adoption reaches the backend with the pet on it, not in it") {
            val appender = mdcKeeping()
            appender.start()
            backend.addAppender(appender)
            try {
                story {
                    When("Ada adopts Nibbles") {
                        asMainDoes { testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(1), "Ada") } }
                    }
                    val adopted =
                        Then(
                            "logback received the adoption",
                        ) { appender.list.single { it.message.contains("adopted") } }
                    And("the pet and the adopter are in its MDC, which is what a pattern and a field search read") {
                        (adopted.mdcPropertyMap["pet_id"] shouldBe "1") and
                            (adopted.mdcPropertyMap["adopted_by"] shouldBe "Ada")
                    }
                }
            } finally {
                backend.detachAppender(appender)
                appender.stop()
            }
        }
    }

    // Logback reads an event's MDC lazily and the pairs leave the MDC once the logging call returns, so
    // take it while appending.
    private fun mdcKeeping() = object : ListAppender<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            event.prepareForDeferredProcessing()
            super.append(event)
        }
    }

    // A suite binds each test's logger to a capture of its own; a thread it did not fork has none, as main has none.
    private fun <A> asMainDoes(block: () -> A): A =
        Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory()).use { it.submit(block).get() }
}
