package petshop.app

import io.github.matthewjones372.lark.app.runApp
import io.github.matthewjones372.pelican.pekko.PelicanServer
import kotlin.system.exitProcess

fun main() {
    val exit = runApp(petshop) { server: PelicanServer ->
        println("Petshop on ${server.baseUrl}, docs at ${server.baseUrl}/api-docs")
        server.block()
    }
    exitProcess(exit.code)
}
