import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    compilerOptions {
        // IntelliJ's ToolWindowFactory supplies deprecated compatibility defaults.
        // Inheriting JVM default methods directly prevents Kotlin from generating
        // synthetic overrides that Plugin Verifier reports as deprecated usages.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation(libs.cvss.calculator)
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
    }
}

intellijPlatform {
    // Veröffentlichungen sind nur dann im Marketplace verborgen, wenn dies
    // explizit über die Gradle-Property aktiviert wird.
    publishing {
        hidden.set(
            providers.gradleProperty("marketplaceHidden")
                .map { it.toBoolean() }
                .orElse(false)
        )
    }
}

// Der Vue-Plugin wird von MavenUp nicht benötigt. In der Test-Sandbox verursacht dessen
// Initialisierung (VueLspServerSupportProvider) sporadisch TestLoggerAssertionErrors, wenn
// die gebündelte Plugin-Distribution des Test-Environments unvollständig ist. Da MavenUp keine
// Abhängigkeit zu Vue hat, wird es für Testläufe deaktiviert, um flakige Fehlschläge zu vermeiden.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareTestSandbox") {
    disabledPlugins.add("org.jetbrains.plugins.vue")
}
