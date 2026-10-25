package petshop.app

import io.github.matthewjones372.surefold.junit.Expectations
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBe as thrownUnlessEqual

/** [shouldBe] for inside `eventually`, which retries only a block that throws: it throws until [expected] holds. */
infix fun <A> A.shouldBeOrRetry(expected: A): Expectations = (this thrownUnlessEqual expected) shouldBe expected
