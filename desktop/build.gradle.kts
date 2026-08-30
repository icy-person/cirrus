import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrainsCompose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}

compose.desktop {
    application {
        mainClass = "dev.klaiber.cirrus.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Cirrus"
            packageVersion = "2.1.1"
            description = "A capable, local-first chat client for Ollama."

            // One mark, three containers. Each platform's packager only reads its own format, and
            // a missing `iconFile` is not an error — it silently ships the generic Java coffee
            // cup, which is how the app came to have no icon at all. The sources are rendered from
            // the same geometry as Android's `ic_launcher_foreground.xml`.
            macOS {
                iconFile.set(project.file("icons/Cirrus.icns"))
                bundleID = "dev.klaiber.cirrus"
            }
            windows { iconFile.set(project.file("icons/Cirrus.ico")) }
            linux { iconFile.set(project.file("icons/Cirrus.png")) }
        }
    }
}
