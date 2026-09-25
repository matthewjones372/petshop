package petshop.registry

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.golden.Golden
import io.github.matthewjones372.pelican.test.golden.requestsOnly
import org.junit.jupiter.api.Test

/**
 * The shop's reading of the registry's contract, pinned.
 *
 * This is the other direction from the shop's own goldens: the registry is somebody else's, so a
 * change here is the shop changing what it believes the registry accepts. That is worth a failing
 * test and a look at the registry's own document before it merges.
 */
class GoldenSpec {

    private val golden = Golden()

    @Test
    fun `the registry's operations are the ones the shop was written against`() {
        golden.operations(registrySpec())
    }

    @Test
    fun `the requests the shop sends are the ones it sent`() {
        val client = requestsOnly(JacksonCodecs)

        golden.request("lookup-chip", client.request(lookupChip, 1L))
        golden.request("record-keeper", client.request(recordKeeper, In2("981000000000001", NewKeeper("Ada"))))
    }
}
