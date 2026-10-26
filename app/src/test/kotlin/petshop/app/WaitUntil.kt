package petshop.app

import io.github.matthewjones372.surefold.test.GaveUp
import io.github.matthewjones372.surefold.test.eventually
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * What [read] answers once [done] holds of it, read again until then, or its last answer once [within] has passed,
 * for a surefold-junit check to follow: `eventually` retries only on a throw, and those checks are values.
 */
fun <A> waitUntil(within: Duration, done: (A) -> Boolean, read: () -> A): A {
    val last = AtomicReference<Answer<A>?>()
    return try {
        eventually(within) {
            last.set(null)
            val answer = read()
            last.set(Answer(answer))
            if (!done(answer)) throw NotYet(answer)
            answer
        }
    } catch (gaveUp: GaveUp) {
        (last.get() ?: throw gaveUp).value
    }
}

private class Answer<A>(val value: A)

private class NotYet(answer: Any?) : AssertionError("not yet: $answer")
