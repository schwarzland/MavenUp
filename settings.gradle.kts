import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "MavenUp"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
        id("org.jetbrains.changelog") version "2.5.0"
        id("io.gitlab.arturbosch.detekt") version "1.23.8"
        id("org.jetbrains.kotlinx.kover") version "0.9.9"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("org.jsoup:jsoup:1.23.2")
            force("com.fasterxml.jackson.core:jackson-core:2.22.2")
            force("com.fasterxml.jackson.core:jackson-databind:2.22.2")
            force("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
        }
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
