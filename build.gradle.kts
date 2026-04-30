plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "de.nogaemer"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("org.jetbrains.kotlin")  // Kotlin PSI + rename refactoring
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        name.set("i18n Strings Editor")
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
        changeNotes = """
            1.0.0 — Initial release. Visual editor for Lyricist Kotlin i18n files.
        """.trimIndent()
    }
}