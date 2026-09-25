package petshop.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.request
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.extension.Parameters
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.http.HttpHeader
import com.github.tomakehurst.wiremock.http.HttpHeaders
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.http.ResponseDefinition
import com.github.tomakehurst.wiremock.matching.ContentPattern
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.matching.UrlPattern
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.test.golden.requestsOnly
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.time.Duration

/**
 * A WireMock server that is stubbed and verified in the endpoint values a Pelican contract is made
 * of, rather than in URLs and JSON strings.
 *
 * ```
 * @RegisterExtension val registry = PelicanWireMock()
 *
 * registry.stub(lookupChip, 1L) answers ok(ChipRecord("981000000000001", keeper = "Petshop"))
 * registry.stub(lookupChip, 2L) answers noSuchChip(Problem("never chipped"))
 * registry.stub(recordKeeper, In2("981000000000001", NewKeeper("Ada"))) fails Fault.CONNECTION_RESET_BY_PEER
 * registry.verify(recordKeeper, In2("981000000000001", NewKeeper("Ada")))
 * ```
 *
 * The path, the query, the body a stub matches and the body it answers with all come from the
 * endpoint, so a renamed path or a changed payload moves every stub with it — or stops the test
 * compiling — instead of leaving a stub that quietly answers 404. An answer can only be a success
 * or a failure the endpoint declares, and it is rendered by Pelican's own server code, so the status,
 * the content type and the JSON are the ones a real Pelican service would send.
 *
 * The server starts when this is constructed, so [baseUrl] can be read in a property initialiser,
 * and stops after each test.
 */
class PelicanWireMock(private val codecs: Codecs = JacksonCodecs) : AfterEachCallback {

    /** Each stub's handler, keyed by the id its WireMock mapping carries. */
    private val answers = ConcurrentHashMap<String, (ClientRequest) -> ClientResponse>()

    private val renderer = object : ResponseDefinitionTransformerV2 {
        override fun getName(): String = RENDERER

        override fun applyGlobally(): Boolean = false

        override fun transform(event: ServeEvent): ResponseDefinition {
            val answer = answers.getValue(event.transformerParameters.getString(STUB))(event.asPelican())
            return ResponseDefinitionBuilder.like(event.responseDefinition)
                .withStatus(answer.status)
                .withHeaders(HttpHeaders(answer.headers.filter { (name, _) -> name.equals("Content-Type", true) }
                    .map { (name, value) -> HttpHeader(name, value) }))
                .withBody(answer.text())
                .build()
        }
    }

    private val server = WireMockServer(wireMockConfig().dynamicPort().extensions(renderer)).apply { start() }

    /** Where the stubs answer. Hand this to whatever builds the client under test. */
    val baseUrl: String = server.baseUrl()

    override fun afterEach(context: ExtensionContext) = server.stop()

    /** Stubs one call: [endpoint] asked with exactly [input]. Say what it answers with on the result. */
    fun <I, E : Any, T : Any> stub(endpoint: Endpoint<I, Outcome<E, T>>, input: I): Stubbing<I, E, T> =
        Stubbing(endpoint, exactly(endpoint, input).let { (method, url, body) ->
            request(method.name, url).also { mapping -> body?.let(mapping::withRequestBody) }
        })

    /**
     * Stubs every call to [endpoint], answering each from its decoded input: a stand-in for the whole
     * operation rather than for one call to it.
     */
    fun <I, E : Any, T : Any> stub(endpoint: Endpoint<I, Outcome<E, T>>, answer: (I) -> Outcome<E, T>) {
        answering(anyCallTo(endpoint), endpoint, answer, Duration.ZERO)
    }

    /** Fails unless [endpoint] was asked with exactly [input], [times] times. */
    fun <I> verify(endpoint: Endpoint<I, *>, input: I, times: Int = 1) {
        val (method, url, body) = exactly(endpoint, input)
        val pattern = RequestPatternBuilder(RequestMethod.fromString(method.name), url)
        body?.let(pattern::withRequestBody)
        server.verify(times, pattern)
    }

    /** How many calls reached [endpoint], whatever they asked. */
    fun calls(endpoint: Endpoint<*, *>): Int =
        server.countRequestsMatching(
            RequestPatternBuilder(RequestMethod.fromString(endpoint.method.name), pathOf(endpoint)).build(),
        ).count

    /** One stubbed call, waiting to be told what it answers with. */
    inner class Stubbing<I, E : Any, T : Any> internal constructor(
        private val endpoint: Endpoint<I, Outcome<E, T>>,
        private val mapping: MappingBuilder,
    ) {
        /** `ok(value)`, or a declared failure: `notFound(Problem(...))`. Nothing else compiles. */
        infix fun answers(outcome: Outcome<E, T>) = answers(outcome, after = Duration.ZERO)

        /** The same answer, [after] it has kept the caller waiting. */
        fun answers(outcome: Outcome<E, T>, after: Duration) = answering(mapping, endpoint, { outcome }, after)

        /** The connection itself goes wrong: reset, closed, or garbage on the wire. */
        infix fun fails(fault: Fault) {
            server.stubFor(mapping.willReturn(aResponse().withFault(fault)))
        }

        /**
         * A status the endpoint never declared — a 500, a gateway's 502 — which is the one answer a
         * contract cannot describe and a client still has to survive.
         */
        infix fun breaksWith(status: Int) {
            server.stubFor(mapping.willReturn(aResponse().withStatus(status).withBody("stubbed $status")))
        }
    }

    private fun <I, E : Any, T : Any> answering(
        mapping: MappingBuilder,
        endpoint: Endpoint<I, Outcome<E, T>>,
        answer: (I) -> Outcome<E, T>,
        after: Duration,
    ) {
        // A Pelican API of one endpoint, called in memory: routing, decoding and response building are
        // the ones a bound server runs, so the answer is rendered exactly as the real service would.
        val served: ClientTransport = InMemoryClientTransport(
            api(listOf(endpoint handledOrFail { input -> answer(input) }), codecs),
        )
        val id = UUID.randomUUID().toString()
        answers[id] = { request -> served.send(request).toCompletableFuture().get() }
        server.stubFor(
            mapping.willReturn(
                aResponse()
                    .withTransformers(RENDERER)
                    .withTransformerParameters(Parameters.one(STUB, id))
                    .withFixedDelay(after.inWholeMilliseconds.toInt()),
            ),
        )
    }

    /** What [endpoint] asked with [input] puts on the wire, built the way the typed client builds it. */
    private fun <I> exactly(endpoint: Endpoint<I, *>, input: I): Triple<Method, UrlPattern, ContentPattern<*>?> {
        val sent = requestsOnly(codecs).request(endpoint, input)
        val json = sent.headers.any { (name, value) -> name.equals("Content-Type", true) && "json" in value }
        return Triple(sent.method, urlEqualTo(sent.target), sent.body?.let { if (json) equalToJson(it) else equalTo(it) })
    }

    private fun anyCallTo(endpoint: Endpoint<*, *>): MappingBuilder = request(endpoint.method.name, pathOf(endpoint))

    /** The endpoint's path template as a pattern: literals as written, a capture as one segment. */
    private fun pathOf(endpoint: Endpoint<*, *>): UrlPattern = urlPathMatching(
        "/" + endpoint.pathSpec.segments.joinToString("/") { segment ->
            when (segment) {
                is PathSegment.Literal -> Pattern.quote(segment.value)
                is PathSegment.Capture -> "[^/]+"
            }
        },
    )

    private fun ServeEvent.asPelican(): ClientRequest {
        val body = request.bodyAsString
        return ClientRequest(
            Method.valueOf(request.method.value()),
            "http://stub${request.url}",
            request.headers.all().flatMap { header -> header.values().map { header.key() to it } },
            if (body.isNullOrEmpty()) ClientRequest.Body.Empty else ClientRequest.Body.Text(body),
        )
    }

    private companion object {
        const val RENDERER = "pelican-answer"
        const val STUB = "stub"
    }
}
