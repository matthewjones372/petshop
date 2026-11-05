package petshop.app

import dev.surefold.app.runApp
import dev.surefold.logInfo
import kotlin.system.exitProcess

fun main() {
    // Before the graph starts, so the settings are on the record even when the start fails.
    logInfo(configReport())
    exitProcess(runApp(Petshop).code)
}
