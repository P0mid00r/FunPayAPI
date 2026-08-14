plugins {
    kotlin("jvm") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.10"
    id("maven-publish")
    id("signing")
}

group = "io.github.p0mid00r"
version = providers.gradleProperty("version").orNull ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("de.jensklingenberg.ktorfit:ktorfit-lib:2.7.5")
    ksp("de.jensklingenberg.ktorfit:ktorfit-ksp:2.7.5")
    implementation("com.fleeksoft.ksoup:ksoup-jvm:0.2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:3.5.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from("LICENSE")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.p0mid00r"
            artifactId = "funpayapi"
            version = project.version.toString()

            from(components["java"])
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            pom {
                name.set("FunPayAPI")
                description.set("Coroutine-first JVM client for FunPay (Ktorfit + Ktor + Ksoup)")
                url.set("https://github.com/P0mid00r/FunPayAPI")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("P0mid00r")
                        name.set("P0mid00r")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/P0mid00r/FunPayAPI.git")
                    developerConnection.set("scm:git:ssh://git@github.com/P0mid00r/FunPayAPI.git")
                    url.set("https://github.com/P0mid00r/FunPayAPI")
                }
            }
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingKey").orNull
    val signingPassword = providers.gradleProperty("signingPassword").orNull
    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
