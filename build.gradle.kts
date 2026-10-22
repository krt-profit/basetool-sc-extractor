import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "com.basetool"
version = (System.getenv("APP_VERSION") ?: findProperty("appVersion") as String?)
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
    ?: "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.components:components-resources:1.12.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")

    testImplementation(kotlin("test"))
    testImplementation("com.networknt:json-schema-validator:3.0.8")
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val versionValue = project.version.toString()
    val outDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
    inputs.property("version", versionValue)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().asFile.resolve("com/basetool/bpextractor/BuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.basetool.bpextractor
            |
            |/** Generated from the project version (the release tag in CI). Do not edit. */
            |internal object BuildInfo {
            |    const val VERSION: String = "$versionValue"
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    jvmToolchain(27)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26)
    }
    sourceSets.named("main") {
        kotlin.srcDir(generateBuildInfo)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildInfo)
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.basetool.bpextractor.resources"
    generateResClass = always
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val ortNativeRoot = layout.buildDirectory.dir("ort-native")
val extractOrtNative = tasks.register<Sync>("extractOrtNative") {
    from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("onnxruntime-") }.map { zipTree(it) } }) {
        include("ai/onnxruntime/native/win-x64/*.dll")
        eachFile { relativePath = RelativePath(true, "windows-x64", name) }
    }
    includeEmptyDirs = false
    into(ortNativeRoot)
}
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(extractOrtNative)
}

compose.desktop {
    application {
        mainClass = "com.basetool.bpextractor.MainKt"

        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(27))
        }.get().metadata.installationPath.asFile.absolutePath

        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)

            packageName = "Basetool SC Extractor"
            packageVersion = project.version.toString().substringBefore('-').ifBlank { "1.0.0" }
            description = "Extracts Star Citizen data (blueprints from Game.log, refinery orders from screenshots) into JSON."
            vendor = "Basetool"
            copyright = "© 2026 Basetool. GPL-3.0-or-later. Unofficial Star Citizen fan tool, not affiliated with the Cloud Imperium group of companies. Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered trademarks of Cloud Imperium Rights LLC."

            modules("java.instrument", "jdk.unsupported", "java.net.http", "jdk.management")

            appResourcesRootDir.set(ortNativeRoot)

            windows {
                upgradeUuid = "530c8db7-fe35-4e3f-8ac4-1af9a611a0a6"

                menuGroup = "Basetool"
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true

                val icon = project.file("src/main/resources/app.ico")
                if (icon.exists()) {
                    iconFile.set(icon)
                }
            }
        }
    }
}

val emptyWixToolsetDir = layout.buildDirectory.dir("no-bundled-wix")
val createEmptyWixToolsetDir = tasks.register("createEmptyWixToolsetDir") {
    outputs.dir(emptyWixToolsetDir)
    doLast { emptyWixToolsetDir.get().asFile.mkdirs() }
}
tasks.withType<AbstractJPackageTask>().configureEach {
    dependsOn(createEmptyWixToolsetDir)
    wixToolsetDir.set(emptyWixToolsetDir)
}

tasks.matching { it.name == "packageMsi" || it.name == "packageReleaseMsi" }.configureEach {
    doFirst {
        val wixExe = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it.trim('"'), "wix.exe") }
            .firstOrNull { it.isFile }
        val reported = wixExe?.let { exe ->
            runCatching {
                val process = ProcessBuilder(exe.absolutePath, "--version").redirectErrorStream(true).start()
                process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
            }.getOrNull()?.lineSequence()?.firstOrNull()?.trim()
        }
        val major = reported?.substringBefore('.')?.toIntOrNull()
        if (major == null || major < 4) {
            val found = if (wixExe == null) "no wix.exe on PATH" else "$wixExe reports '${reported ?: "nothing"}'"
            throw GradleException(
                "The MSI needs WiX 4+ first on PATH ($found). Build it with .\\package-msi.ps1, which " +
                    "selects or bootstraps the pinned, checksum-verified WiX 7 - not with gradlew packageMsi.",
            )
        }
    }
}
