package petshop.api

import io.github.matthewjones372.pelican.openapi.Docs
import io.github.matthewjones372.pelican.openapi.docs

val petshopDocs: Docs = docs {
    docsPath = "/api-docs"
    scalarReference("/reference") {
        theme {
            accent = TERRACOTTA
            text = BARK
            sidebar = CREAM
            success = SAGE
            error = RUST
            font("Nunito, sans-serif", stylesheet = NUNITO)
            codeFont("'JetBrains Mono', monospace", stylesheet = JETBRAINS_MONO)
        }
    }
}

private const val TERRACOTTA = "#C2562B"
private const val RUST = "#9A3A1A"
private const val SAGE = "#5E8C61"
private const val BARK = "#3B2A20"
private const val CREAM = "#FBF6EF"

private const val NUNITO = "https://fonts.googleapis.com/css2?family=Nunito:wght@400;600;800&display=swap"
private const val JETBRAINS_MONO = "https://fonts.googleapis.com/css2?family=JetBrains+Mono&display=swap"
