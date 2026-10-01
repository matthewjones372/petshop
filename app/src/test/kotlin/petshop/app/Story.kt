package petshop.app

import arrow.core.nonFatalOrThrow
import io.github.matthewjones372.lark.clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toKotlinDuration

/*
 * A prototype of Lark specs 0115 (a test that reads as a story) and 0116 (a story in colour), here so the
 * output can be seen and argued with before the specs are settled. It moves to lark-test when they are.
 */

/**
 * Runs [block] as a story named [title], by default the calling test's own name. Prints the story's steps
 * once it ends, and if a step throws, throws a [StoryFailed] whose message is the story up to that step.
 */
fun story(title: String = callingTest(), block: Story.() -> Unit) {
    val story = Story()
    try {
        story.block()
    } catch (failure: Throwable) {
        println(story.told(title, Colour.wanted))
        throw StoryFailed(story.told(title, colour = false), failure.nonFatalOrThrow())
    }
    println(story.told(title, Colour.wanted))
}

/** A story that failed. The message is the transcript, plain, so it reads the same in CI as in an IDE. */
class StoryFailed(transcript: String, cause: Throwable) : AssertionError(transcript, cause)

class Story internal constructor() {

    private val steps = mutableListOf<Step>()
    private var depth = 0

    fun <A> Given(text: String, block: () -> A): A = step("Given", text, block)
    fun <A> When(text: String, block: () -> A): A = step("When", text, block)
    fun <A> Then(text: String, block: () -> A): A = step("Then", text, block)
    fun <A> And(text: String, block: () -> A): A = step("And", text, block)
    fun <A> But(text: String, block: () -> A): A = step("But", text, block)

    fun Given(text: String) = Waiting("Given", text)
    fun When(text: String) = Waiting("When", text)
    fun Then(text: String) = Waiting("Then", text)
    fun And(text: String) = Waiting("And", text)
    fun But(text: String) = Waiting("But", text)

    /** A step that waits for its block to stop throwing. */
    inner class Waiting internal constructor(private val keyword: String, private val text: String) {

        /**
         * Runs [block] again every [every] until it returns, or [within] has passed on Lark's clock, when the
         * last failure is rethrown. Time rather than tries, so a slow try does not stretch the wait.
         */
        fun <A> eventually(within: Duration, every: Duration = 20.milliseconds, block: () -> A): A =
            step(keyword, text) { retried(within, every, steps.last { it.running }, block) }
    }

    private fun <A> step(keyword: String, text: String, block: () -> A): A {
        val step = Step(keyword, text, depth)
        steps += step
        val started = System.nanoTime()
        depth++
        try {
            return block().also { step.outcome = Outcome.Passed }
        } catch (failure: Throwable) {
            // Only the innermost step that saw this failure says what it was; the steps around it just failed.
            val seen = steps.any { it !== step && (it.outcome as? Outcome.Failed)?.failure === failure }
            step.outcome = Outcome.Failed(failure, told = !seen)
            throw failure
        } finally {
            depth--
            step.took = (System.nanoTime() - started).nanoseconds
        }
    }

    private fun <A> retried(within: Duration, every: Duration, step: Step, block: () -> A): A {
        val time = clock.get()
        val start = time.now()
        while (true) {
            step.tries++
            try {
                return block()
            } catch (failure: Throwable) {
                failure.nonFatalOrThrow()
                if (java.time.Duration.between(start, time.now()).toKotlinDuration() >= within) throw failure
                time.sleep(every)
            }
        }
    }

    internal fun told(title: String, colour: Boolean): String {
        val ink = Ink(colour)
        val heads = steps.map { "  ".repeat(it.depth + 1) + "${it.mark} ${it.keyword} ${it.text}" }
        val width = heads.maxOfOrNull { it.length } ?: 0
        return buildString {
            append(ink.bold("Story: $title"))
            steps.zip(heads).forEach { (step, head) ->
                append('\n')
                val padded = head.padEnd(width + 4)
                append(
                    when (step.outcome) {
                        is Outcome.Failed -> ink.red(ink.bold(padded))
                        else -> padded.replaceFirst("✓", ink.green("✓"))
                    },
                )
                append(ink.dim(step.timing()))
                val failed = step.outcome as? Outcome.Failed
                if (failed != null && failed.told) {
                    val indent = "  ".repeat(step.depth + 3)
                    "${failed.failure.message ?: failed.failure}".lines().forEach { append('\n').append(ink.red(indent + it)) }
                }
            }
        }
    }

    private class Step(val keyword: String, val text: String, val depth: Int) {
        var outcome: Outcome? = null
        var took: Duration = Duration.ZERO
        var tries = 0
        val running: Boolean get() = outcome == null
        val mark: String get() = if (outcome is Outcome.Failed) "✗" else "✓"

        fun timing(): String {
            val time = if (took < 1000.milliseconds) "${took.inWholeMilliseconds} ms" else "%.1f s".format(took.inWholeMilliseconds / 1000.0)
            return if (tries > 1) "$time, $tries tries" else time
        }
    }

    private sealed interface Outcome {
        data object Passed : Outcome
        class Failed(val failure: Throwable, val told: Boolean) : Outcome
    }
}

/**
 * Whether the console gets colour, decided once: `-Dlark.test.colour=always|never` wins, then `NO_COLOR`
 * turns it off, then `FORCE_COLOR` or running under IntelliJ turns it on, and otherwise it is off.
 */
object Colour {
    val wanted: Boolean by lazy {
        decide(
            property = System.getProperty("lark.test.colour"),
            noColour = System.getenv("NO_COLOR"),
            forceColour = System.getenv("FORCE_COLOR"),
            underIntelliJ = System.getProperty("idea.test.cyclic.buffer.size") != null,
        )
    }

    fun decide(property: String?, noColour: String?, forceColour: String?, underIntelliJ: Boolean): Boolean = when {
        property == "always" -> true
        property == "never" -> false
        !noColour.isNullOrEmpty() -> false
        !forceColour.isNullOrEmpty() -> true
        else -> underIntelliJ
    }
}

private class Ink(private val on: Boolean) {
    fun bold(text: String) = paint("1", text)
    fun dim(text: String) = paint("2", text)
    fun red(text: String) = paint("31", text)
    fun green(text: String) = paint("32", text)
    private fun paint(code: String, text: String) = if (on) "\u001B[${code}m$text\u001B[0m" else text
}

/** The test method that called [story]: the first frame outside this file, which is a JUnit method's own name. */
private fun callingTest(): String =
    StackWalker.getInstance().walk { frames ->
        frames.filter { it.className != "petshop.app.StoryKt" }.findFirst().map { it.methodName }.orElse("a story")
    }
