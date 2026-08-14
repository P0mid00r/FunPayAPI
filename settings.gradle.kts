plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

nmcpSettings {
    centralPortal {
        username = System.getenv("SONATYPE_USERNAME") ?: ""
        password = System.getenv("SONATYPE_PASSWORD") ?: ""
        publishingType = "AUTOMATIC"
        publicationName = "funpayapi"
        validationTimeout = java.time.Duration.of(30, java.time.temporal.ChronoUnit.MINUTES)
    }
}

rootProject.name = "FunPayAPI"
include(":example")
