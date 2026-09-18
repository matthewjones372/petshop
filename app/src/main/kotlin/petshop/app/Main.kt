package petshop.app

import io.github.matthewjones372.lark.app.runApp
import io.github.matthewjones372.lark.logger
import kotlin.system.exitProcess

/**
 * The backend is bound around everything, so every recipe and every fork writes through it.
 *
 * Outside this block a line reaches `StderrLogger`, which is a second format for one service to
 * print in and nothing a log aggregator can read.
 */
fun main() {
    val exit = logger.locally(Slf4jLogger()) { runApp(Petshop) }
    exitProcess(exit.code)
}
