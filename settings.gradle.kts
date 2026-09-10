plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "petshop"

include("domain")
include("api")
include("app")
include("loadtest")
