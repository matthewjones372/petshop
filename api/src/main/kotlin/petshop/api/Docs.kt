package petshop.api

import io.github.matthewjones372.pelican.openapi.Docs
import io.github.matthewjones372.pelican.openapi.docs

val petshopDocs: Docs = docs {
    docsPath = "/api-docs"
    rapiDocReference("/reference") {
        theme {
            accent = TERRACOTTA
            text = BARK
            sidebar = CREAM
            success = SAGE
            error = RUST
            font("Nunito, sans-serif", stylesheet = NUNITO)
            codeFont("'JetBrains Mono', monospace", stylesheet = JETBRAINS_MONO)
            logo(PAW, altText = "Petshop", backgroundColor = CREAM)
        }
        // One server and no credentials: RapiDoc's pickers for either would offer nothing to choose.
        attributes("allow-server-selection" to "false", "allow-authentication" to "false")
    }
}

private const val TERRACOTTA = "#C2562B"
private const val RUST = "#9A3A1A"
private const val SAGE = "#5E8C61"
private const val BARK = "#3B2A20"
private const val CREAM = "#FBF6EF"

private const val NUNITO = "https://fonts.googleapis.com/css2?family=Nunito:wght@400;600;800&display=swap"
private const val JETBRAINS_MONO = "https://fonts.googleapis.com/css2?family=JetBrains+Mono&display=swap"

private const val PAW =
    "data:image/svg+xml;utf8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' width='96' height='96'%3E" +
        "%3Cg fill='%23C2562B'%3E" +
        "%3Cellipse cx='5.5' cy='10' rx='2.2' ry='2.8'/%3E" +
        "%3Cellipse cx='9.5' cy='5.5' rx='2.2' ry='2.9'/%3E" +
        "%3Cellipse cx='14.5' cy='5.5' rx='2.2' ry='2.9'/%3E" +
        "%3Cellipse cx='18.5' cy='10' rx='2.2' ry='2.8'/%3E" +
        "%3Cpath d='M12 11c-3.2 0-6.5 4.3-6.5 7.2 0 2 1.6 2.8 3.4 2.8 1.3 0 2.1-.6 3.1-.6s1.8.6 3.1.6" +
        "c1.8 0 3.4-.8 3.4-2.8C18.5 15.3 15.2 11 12 11z'/%3E" +
        "%3C/g%3E%3C/svg%3E"
