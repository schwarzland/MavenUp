package de.schwarzland.mavenup.service

import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Prüft die Property-Erkennung und Versionsauflösung anhand der Referenz-POMs 01–08.
 */
class PropertiesInPomExamplesIntegrationTest : BasePlatformTestCase() {

    /** Eine Fixture-POM mit ihrem XML-Wurzel-Tag und den lokal deklarierten Properties. */
    private data class FixturePom(
        val rootTag: XmlTag,
        val localProperties: Map<String, String>
    )

    /** Erkannte Versionen und die Property-Namen ihrer Deklarationen. */
    private data class CollectedEntries(
        val versions: Map<String, String>,
        val propertyNames: Map<String, String>
    )

    /**
     * Lädt eine Fixture-POM als PSI-Datei und liest ihre lokalen Properties.
     *
     * @param scenario Verzeichnisname des Beispielprojekts.
     * @param relativePom Pfad der POM innerhalb des Beispielprojekts.
     * @return Wurzel-Tag und lokale Property-Werte der geladenen POM.
     */
    private fun loadPom(scenario: String, relativePom: String = "pom.xml"): FixturePom {
        val fixtureFile = File("src/test/resources/properties-in-pom/$scenario/$relativePom")
        assertTrue("Fixture POM must exist: ${fixtureFile.absolutePath}", fixtureFile.isFile)

        val fixturePath = "properties-in-pom/$scenario/$relativePom"
        val psiFile = myFixture.addFileToProject(fixturePath, fixtureFile.readText()) as XmlFile
        val rootTag = psiFile.document?.rootTag
        assertNotNull("Fixture POM must have a root tag: $fixturePath", rootTag)
        val properties = rootTag!!.findFirstSubTag("properties")?.subTags.orEmpty()
            .associate { property -> property.name to property.value.text.trim() }

        return FixturePom(rootTag, properties)
    }

    /**
     * Erfasst die Einträge eines Dependency- oder Plugin-Abschnitts und löst einfache Property-Verweise auf.
     *
     * @param containerTag Abschnitt mit dem Wrapper-Tag oder der Wrapper selbst.
     * @param wrapperName Name des Wrappers, etwa `dependencies` oder `plugins`.
     * @param itemName Name des enthaltenen Eintrags, etwa `dependency` oder `plugin`.
     * @param effectiveProperties Properties, die für diese POM effektiv gelten.
     * @return Versionen nach Koordinate und erkannte Property-Namen.
     */
    private fun collectEntries(
        containerTag: XmlTag?,
        wrapperName: String,
        itemName: String,
        effectiveProperties: Map<String, String>
    ): CollectedEntries {
        val declaredVersions = mutableMapOf<String, String>()
        val propertyNames = mutableMapOf<String, String>()
        val collector = RefreshSnapshotCollector(project)
        collector.collectDependenciesAndProperties(
            containerTag,
            wrapperName,
            itemName,
            declaredVersions,
            propertyNames
        )

        return CollectedEntries(
            versions = declaredVersions.mapValues { (_, declaration) ->
                collector.resolveVersionPlaceholder(declaration, effectiveProperties)
            },
            propertyNames = propertyNames
        )
    }

    /** Prüft die Auflösung einer direkten Abhängigkeit über eine lokale Property. */
    fun testScenario01_LocalProperty() {
        val pom = loadPom("01-local-property")
        val dependencies = collectEntries(pom.rootTag, "dependencies", "dependency", pom.localProperties)

        assertEquals(mapOf("junit:junit" to "4.13.2"), dependencies.versions)
        assertEquals(mapOf("junit:junit" to "junit.version"), dependencies.propertyNames)
    }

    /** Prüft, dass mehrere direkte Abhängigkeiten dieselbe Property verwenden können. */
    fun testScenario02_SharedProperty() {
        val pom = loadPom("02-shared-property")
        val dependencies = collectEntries(pom.rootTag, "dependencies", "dependency", pom.localProperties)

        assertEquals(
            mapOf(
                "com.fasterxml.jackson.core:jackson-core" to "2.17.2",
                "com.fasterxml.jackson.core:jackson-databind" to "2.17.2"
            ),
            dependencies.versions
        )
        assertEquals(setOf("jackson.version"), dependencies.propertyNames.values.toSet())
    }

    /** Prüft Property-Versionen in Plugin-Management und in direkten Plugin-Deklarationen. */
    fun testScenario03_PluginProperties() {
        val pom = loadPom("03-plugin-property")
        val buildTag = pom.rootTag.findFirstSubTag("build")
        val directPlugins = collectEntries(buildTag, "plugins", "plugin", pom.localProperties)
        val managedPlugins = collectEntries(
            buildTag?.findFirstSubTag("pluginManagement"),
            "plugins",
            "plugin",
            pom.localProperties
        )

        assertEquals(
            mapOf(
                "org.apache.maven.plugins:maven-surefire-plugin" to "3.2.5",
                "org.apache.maven.plugins:maven-compiler-plugin" to ""
            ),
            directPlugins.versions
        )
        assertEquals("surefire.version", directPlugins.propertyNames["org.apache.maven.plugins:maven-surefire-plugin"])
        assertEquals(
            mapOf("org.apache.maven.plugins:maven-compiler-plugin" to "3.13.0"),
            managedPlugins.versions
        )
        assertEquals("compiler.version", managedPlugins.propertyNames["org.apache.maven.plugins:maven-compiler-plugin"])
    }

    /** Prüft Property-Auflösung in Dependency-Management und eine direkte Version ohne eigene Deklaration. */
    fun testScenario04_DependencyManagementProperty() {
        val pom = loadPom("04-dependency-management")
        val managed = collectEntries(
            pom.rootTag.findFirstSubTag("dependencyManagement"),
            "dependencies",
            "dependency",
            pom.localProperties
        )
        val direct = collectEntries(pom.rootTag, "dependencies", "dependency", pom.localProperties)

        assertEquals(mapOf("junit:junit" to "4.13.2"), managed.versions)
        assertEquals("junit.version", managed.propertyNames["junit:junit"])
        assertEquals(mapOf("junit:junit" to ""), direct.versions)
        assertTrue("The direct declaration inherits its version and has no local property", direct.propertyNames.isEmpty())
    }

    /** Prüft, dass eine Child-POM die Property ihres Parent-Projekts erbt. */
    fun testScenario05_ParentProperty() {
        val parent = loadPom("05-parent-property")
        val child = loadPom("05-parent-property", "child/pom.xml")
        val effectiveProperties = parent.localProperties + child.localProperties
        val dependencies = collectEntries(child.rootTag, "dependencies", "dependency", effectiveProperties)

        assertEquals(mapOf("junit:junit" to "4.13.2"), dependencies.versions)
        assertEquals("junit.version", dependencies.propertyNames["junit:junit"])
    }

    /** Prüft, dass der Property-Override des Childs auch die geerbte verwaltete Version bestimmt. */
    fun testScenario06_ChildOverridesParentProperty() {
        val parent = loadPom("06-child-override")
        val child = loadPom("06-child-override", "child/pom.xml")
        val effectiveProperties = parent.localProperties + child.localProperties
        val managed = collectEntries(
            parent.rootTag.findFirstSubTag("dependencyManagement"),
            "dependencies",
            "dependency",
            effectiveProperties
        )
        val direct = collectEntries(child.rootTag, "dependencies", "dependency", effectiveProperties)

        assertEquals("4.12", parent.localProperties["junit.version"])
        assertEquals("4.13.2", child.localProperties["junit.version"])
        assertEquals(mapOf("junit:junit" to "4.13.2"), managed.versions)
        assertEquals(mapOf("junit:junit" to ""), direct.versions)
    }

    /** Prüft die Property-Vererbung über Großeltern, Eltern und lokale Child-Properties. */
    fun testScenario07_MultilevelInheritance() {
        val grandparent = loadPom("07-multilevel-inheritance")
        val parent = loadPom("07-multilevel-inheritance", "parent/pom.xml")
        val child = loadPom("07-multilevel-inheritance", "parent/child/pom.xml")
        val effectiveProperties = grandparent.localProperties + parent.localProperties + child.localProperties
        val dependencies = collectEntries(child.rootTag, "dependencies", "dependency", effectiveProperties)

        assertEquals(
            mapOf(
                "junit:junit" to "4.13.2",
                "org.hamcrest:hamcrest" to "2.2",
                "org.slf4j:slf4j-api" to "2.0.13"
            ),
            dependencies.versions
        )
        assertEquals(
            mapOf(
                "junit:junit" to "junit.version",
                "org.hamcrest:hamcrest" to "hamcrest.version",
                "org.slf4j:slf4j-api" to "slf4j.version"
            ),
            dependencies.propertyNames
        )
    }

    /** Prüft die geerbte Property und den abweichenden lokalen Override in zwei Modulen. */
    fun testScenario08_MultiModulePropertyOverride() {
        val parent = loadPom("08-multi-module")
        val common = loadPom("08-multi-module", "common/pom.xml")
        val override = loadPom("08-multi-module", "override/pom.xml")
        val commonDependencies = collectEntries(
            common.rootTag,
            "dependencies",
            "dependency",
            parent.localProperties + common.localProperties
        )
        val overrideDependencies = collectEntries(
            override.rootTag,
            "dependencies",
            "dependency",
            parent.localProperties + override.localProperties
        )

        assertEquals(mapOf("junit:junit" to "4.12"), commonDependencies.versions)
        assertEquals(mapOf("junit:junit" to "4.13.2"), overrideDependencies.versions)
        assertEquals("junit.version", commonDependencies.propertyNames["junit:junit"])
        assertEquals("junit.version", overrideDependencies.propertyNames["junit:junit"])
    }
}
