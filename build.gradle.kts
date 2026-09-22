import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
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

    // Die von der IntelliJ Platform Gradle Plugin Dependencies Extension bezogene
    // "test-framework"-Artefaktgruppe (testFramework(TestFrameworkType.Platform) unten) zieht
    // auf testCompileClasspath/testRuntimeClasspath transitiv als verwundbar gemeldete
    // Jackson-Versionen ueber ihre jackson-bom-Plattform-Constraint. Diese Artefakte laufen
    // ausschliesslich zur Testzeit und werden nie in das Plugin-JAR gepackt. Die Versionen
    // werden zusaetzlich explizit deklariert, damit Dependency-Scanner und Dependabot die
    // gepinnte Version sehen - `resolutionStrategy.force` allein wird von diesen Werkzeugen
    // nicht ausgewertet.
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.22.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
    }
}

// Nur die Test-Konfigurationen werden gepinnt: `configurations.all` wuerde auch die intern von
// der IntelliJ Platform Gradle Plugin verwalteten Sandbox-/Plattform-Konfigurationen erfassen.
// jackson-module-kotlin wird bewusst NICHT erzwungen: Eine neuere Version zieht eine inkompatible
// kotlin-reflect/kotlin-stdlib-Variante nach sich, was im IDE-Testprozess zu "Debug metadata
// version mismatch"-Fehlern bei coroutine-basierten Tests fuehrt. Da im Produktivcode kein
// jackson-module-kotlin verwendet wird, genuegt es, die tatsaechlich verwundbaren Artefakte
// jackson-core und jackson-databind zu pinnen.
listOf("testCompileClasspath", "testRuntimeClasspath").forEach { configurationName ->
    configurations.named(configurationName) {
        resolutionStrategy {
            force(
                "com.fasterxml.jackson.core:jackson-core:2.22.2",
                "com.fasterxml.jackson.core:jackson-databind:2.22.2",
            )
        }
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

// Statische Code-Analyse via detekt. Die Baseline (config/detekt/baseline.xml) friert bestehende
// Befunde ein, sodass nur neu eingeführte Verstöße den Build scheitern lassen. Die Schwellenwerte
// (u. a. LargeClass = 800) in config/detekt/detekt.yml spiegeln die 800–1000-Zeilen-Regel wider.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    basePath = rootDir.absolutePath
}

// Test-Coverage-Messung via Kover. Erzeugt HTML-/XML-Reports über `koverHtmlReport` bzw.
// `koverXmlReport`; ein blockierendes Mindest-Coverage-Gate kann später über `koverVerify` ergänzt werden.
kover {
    reports {
        total {
            html { onCheck = false }
            xml { onCheck = false }
        }
    }
}

