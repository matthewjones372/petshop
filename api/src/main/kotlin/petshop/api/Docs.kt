package petshop.api

import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.Docs
import io.github.matthewjones372.pelican.openapi.docs

val petshopDocs: Docs = docs {
    docsPath = "/api-docs"
    reference("/reference") {
        logo(PAW, altText = "Petshop", backgroundColor = CREAM)
        stylesheet("https://fonts.googleapis.com/css2?family=Nunito:wght@400;600;800&family=JetBrains+Mono&display=swap")
        options = jsonObj {
            put(
                "theme",
                jsonObj {
                    put(
                        "colors",
                        jsonObj {
                            put("primary", jsonObj { "main" to TERRACOTTA })
                            put("success", jsonObj { "main" to SAGE })
                            put("text", jsonObj { "primary" to BARK })
                            put("responses", jsonObj {
                                put("error", jsonObj { "color" to RUST; "backgroundColor" to "#FBEDE7" })
                            })
                            put("http", jsonObj {
                                "get" to SAGE
                                "post" to TERRACOTTA
                            })
                        },
                    )
                    put(
                        "typography",
                        jsonObj {
                            "fontFamily" to "Nunito, sans-serif"
                            "fontSize" to "15px"
                            "lineHeight" to "1.6"
                            put("headings", jsonObj { "fontFamily" to "Nunito, sans-serif"; "fontWeight" to "800" })
                            put("code", jsonObj { "fontFamily" to "'JetBrains Mono', monospace"; "color" to RUST })
                        },
                    )
                    put("sidebar", jsonObj { "backgroundColor" to CREAM; "textColor" to BARK })
                    put("rightPanel", jsonObj { "backgroundColor" to BARK })
                    put("logo", jsonObj { "maxHeight" to "72px"; "gutter" to "20px" })
                },
            )
            "expandResponses" to "200"
            "hideDownloadButton" to false
        }
    }
}

private const val TERRACOTTA = "#C2562B"
private const val RUST = "#9A3A1A"
private const val SAGE = "#5E8C61"
private const val BARK = "#3B2A20"
private const val CREAM = "#FBF6EF"

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
