package petshop.app

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.DocsBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The shop's API reference at /reference: Redoc beside Swagger's console at /api-docs, reading the same document,
 * dressed as the petshop. A warm cream and a dark brown, a collar's orange for links and POSTs, a vet's green for
 * GETs, a rounded face for headings, and a paw for a logo (Pelican spec 0071).
 */
internal fun DocsBuilder.shopReference() = reference("/reference") {
    logo(PAW, altText = "Petshop", backgroundColor = CREAM_DARK)
    stylesheet(FONTS)
    options = jsonObj {
        put("theme", theme)
        "expandResponses" to "200"
    }
}

private val theme: JsonObj = jsonObj {
    put("colors", jsonObj {
        put("primary", jsonObj { "main" to COLLAR })
        put("success", jsonObj { "main" to VET_GREEN })
        put("error", jsonObj { "main" to "#C2483B" })
        put("text", jsonObj { "primary" to BROWN; "secondary" to "#6E5B50" })
        put("http", jsonObj { "get" to VET_GREEN; "post" to COLLAR; "delete" to "#C2483B" })
    })
    put("typography", jsonObj {
        "fontFamily" to "Nunito, sans-serif"
        "fontSize" to "15px"
        put("headings", jsonObj { "fontFamily" to "Fredoka, sans-serif"; "fontWeight" to "600" })
        put("code", jsonObj { "fontFamily" to "'JetBrains Mono', monospace"; "color" to "#8A4B12"; "backgroundColor" to "#FBEBD7" })
        put("links", jsonObj { "color" to "#B5651D"; "visited" to "#B5651D"; "hover" to COLLAR })
    })
    put("sidebar", jsonObj { "backgroundColor" to CREAM_DARK; "textColor" to BROWN; "activeTextColor" to "#B5651D" })
    put("rightPanel", jsonObj { "backgroundColor" to BROWN; "textColor" to "#FFF4E3" })
    put("logo", jsonObj { "maxHeight" to "96px"; "gutter" to "20px" })
}

private const val COLLAR = "#D9822B"
private const val VET_GREEN = "#3E8E5E"
private const val BROWN = "#3B2F2A"
private const val CREAM_DARK = "#FDF1E2"

private const val FONTS = "https://fonts.googleapis.com/css2?family=Fredoka:wght@500;600" +
    "&family=Nunito:wght@400;600;700&family=JetBrains+Mono:wght@400;500&display=swap"

/** Four toes and a pad, small enough to ride in the page rather than be served from somewhere. */
private val PAW: String = "data:image/svg+xml;utf8," + URLEncoder.encode(
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><g fill="$COLLAR">""" +
        """<ellipse cx="18" cy="22" rx="7" ry="9"/><ellipse cx="32" cy="15" rx="7" ry="9"/>""" +
        """<ellipse cx="46" cy="22" rx="7" ry="9"/><ellipse cx="9" cy="37" rx="6" ry="8"/>""" +
        """<ellipse cx="55" cy="37" rx="6" ry="8"/>""" +
        """<path d="M32 31c-11 0-20 11-20 20 0 6 5 8 10 8 4 0 6-2 10-2s6 2 10 2c5 0 10-2 10-8 0-9-9-20-20-20z"/>""" +
        """</g></svg>""",
    StandardCharsets.UTF_8,
).replace("+", "%20")
