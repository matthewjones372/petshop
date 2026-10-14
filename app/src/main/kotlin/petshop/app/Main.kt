package petshop.app

import io.github.matthewjones372.lark.app.runApp
import io.github.matthewjones372.lark.logInfo
import kotlin.system.exitProcess

fun main() {
    // Before the graph starts, so the settings are on the record even when the start fails.
    logInfo(configReport())
    exitProcess(runApp(Petshop).code)
}
