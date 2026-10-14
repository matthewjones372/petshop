package petshop.app

import com.typesafe.config.ConfigException
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.boundTo
import io.github.matthewjones372.lark.app.choice
import io.github.matthewjones372.lark.app.closingOf
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.singleOf
import io.github.matthewjones372.lark.app.typesafe.Reading
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.client.jetty.JettyHttpTransport
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.docs.startWithDocs as startOnJetty
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs as startOnPekko
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Adapter
import petshop.api.Binders
import petshop.api.JettyBinders
import petshop.api.KotlinxShopCodecs
import petshop.api.PekkoBinders
import petshop.api.petshopDocs

/** Set by `petshop.stack`, overridden by `STACK`. */
enum class Stack { Pekko, Jetty }

/** Set by `petshop.codec`, overridden by `CODEC`. */
enum class Codec { Jackson, Kotlinx }

/** The port the shop is served on, whichever server holds it, and what it was started with. */
class Shopfront(val baseUrl: String, val stack: Stack, val codec: Codec, private val stopping: () -> Unit) {
    fun stop() = stopping()
}

/** What running on Pekko or on Jetty changes: where streams run, how the registry is called, and what serves. */
interface Engine {
    val stack: Stack
    val streams: StreamBackend
    val transport: ClientTransport

    fun binders(streams: StreamBackend): Binders

    fun serve(api: Api, settings: Settings, codec: Codec): Shopfront
}

/** Pekko end to end: Pekko HTTP serves and calls the registry, and lark's streams run as Pekko streams. */
class OnPekko(private val system: ActorSystem) : Engine {
    override val stack = Stack.Pekko
    override val streams: StreamBackend = PekkoStreams(system)
    override val transport: ClientTransport = PekkoHttpTransport(system)

    override fun binders(streams: StreamBackend): Binders = PekkoBinders

    override fun serve(api: Api, settings: Settings, codec: Codec): Shopfront =
        api.startOnPekko(Adapter.toTyped(system), port = settings.port, host = settings.host, docs = petshopDocs)
            .let { server -> Shopfront(server.baseUrl, stack, codec, server::stop) }
}

/** No actor system: Jetty serves and calls the registry, and lark's streams run on virtual threads. */
class OnJetty(override val transport: JettyHttpTransport) : Engine {
    override val stack = Stack.Jetty
    override val streams: StreamBackend = Forks()

    override fun binders(streams: StreamBackend): Binders = JettyBinders(streams)

    override fun serve(api: Api, settings: Settings, codec: Codec): Shopfront =
        api.startOnJetty(port = settings.port, host = settings.host, docs = petshopDocs)
            .let { server -> Shopfront(server.baseUrl, stack, codec, server::stop) }
}

// Functions rather than values, so the stack not chosen is not even described, and its classes stay unloaded.
private fun onPekko(): Module =
    singleOf<ActorSystem>({ ActorSystem.create("petshop") }, { system -> system.terminate() }) +
        singleOf(::OnPekko).boundTo<Engine>()

private fun onJetty(): Module = closingOf { -> JettyHttpTransport() } + singleOf(::OnJetty).boundTo<Engine>()

/** A choice inside the graph, so the stack not chosen contributes no node: on Jetty no ActorSystem is created. */
val engine: Module =
    config<Stack>("petshop") { oneOf("stack", Stack.entries) } +
        choice<Stack, Engine> { stack ->
            when (stack) {
                Stack.Pekko -> onPekko()
                Stack.Jetty -> onJetty()
            }
        } +
        // The one place that picks the stream backend; a test overrides it to run the relay on its own clock.
        single { chosen: Engine -> chosen.streams } +
        single { chosen: Engine, streams: StreamBackend -> chosen.binders(streams) }

val codecs: Module =
    config<Codec>("petshop") { oneOf("codec", Codec.entries) } +
        single { codec: Codec ->
            when (codec) {
                Codec.Jackson -> JacksonCodecs
                Codec.Kotlinx -> KotlinxShopCodecs
            }
        }.boundTo<Codecs>()

/** Spelled in lower case in the file, as `pekko` or `kotlinx`. */
private fun <A : Enum<A>> Reading.oneOf(path: String, entries: List<A>): A =
    of(entries.last()) {
        val named = getString(path)
        entries.firstOrNull { it.name.equals(named, ignoreCase = true) }
            ?: throw ConfigException.BadValue(
                getValue(path).origin(),
                path,
                "'$named' is not ${entries.joinToString(" or ") { it.name.lowercase() }}",
            )
    }
