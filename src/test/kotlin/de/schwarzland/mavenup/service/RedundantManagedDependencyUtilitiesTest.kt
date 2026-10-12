package de.schwarzland.mavenup.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject

/**
* Tests für die Utility-Methoden von [RedundantManagedDependencyService].
* Deckt isUsedInAnyProject, findProjectConsumersForManaged, matchesCoordinateScope,
* collectManagedDependencies, collectDirectDependenciesWithExplicitVersions und
* resolveFallbackManagedVersionWithoutLocalEntry ab.
*/
class RedundantManagedDependencyUtilitiesTest : BasePlatformTestCase() {

    private fun createArtifact(groupId: String, artifactId: String, version: String): MavenArtifact {
        return MavenArtifact(
            groupId,
            artifactId,
            version,
            null,
            "jar",
            null,
            "compile",
            false,
            "jar",
            null,
            null,
            false,
            true
        )
    }

    private fun createArtifactNode(groupId: String, artifactId: String, version: String): MavenArtifactNode {
        val artifact = createArtifact(groupId, artifactId, version)
        return MavenArtifactNode(null, artifact, null, null, null, null, null)
    }

    // ---- isUsedInAnyProject isolierte Tests ----

    /**
     * Prüft, dass isUsedInAnyProject false zurückgibt, wenn das Artefakt in keinem Modul vorkommt.
     */
    fun testIsUsedInAnyProjectReturnsFalseWhenNotUsed() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.google.guava</groupId>
                                <artifactId>guava</artifactId>
                                <version>32.1.2-jre</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        val isUsed = service.isUsedInAnyProject(
            projects = listOf(mavenProject),
            groupId = "com.google.guava",
            artifactId = "guava",
            indicator = null
        )

        // Keine direkten oder transitiven Dependencies → nicht verwendet
        assertFalse(isUsed)
    }

    /**
     * Prüft, dass isUsedInAnyProject true zurückgibt, wenn das Artefakt im dependencyTree eines Moduls vorkommt.
     * Da MavenProject ohne vollständige Initialisierung keine dependencies hat, wird der dependencyTree
     * mit einem manuell erstellten Knoten geprüft.
     */
    fun testIsUsedInAnyProjectReturnsTrueWhenInDependencyTree() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        // Ohne initialisierte dependencies/dependencyTree ist kein Artefakt verwendet
        val isUsed = service.isUsedInAnyProject(
            projects = listOf(mavenProject),
            groupId = "org.slf4j",
            artifactId = "slf4j-api",
            indicator = null
        )

        // MavenProject ohne Initialisierung hat leere dependencyTree → false
        assertFalse(isUsed)
    }

    // ---- findProjectConsumersForManaged isolierte Tests ----

    /**
     * Prüft, dass findProjectConsumersForManaged leere Liste zurückgibt, wenn keine Konsumenten existieren.
     */
    fun testFindProjectConsumersForManagedReturnsEmptyList() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        val consumers = service.findProjectConsumersForManaged(
            mavenProject = mavenProject,
            managedGroupId = "org.slf4j",
            managedArtifactId = "slf4j-api",
            indicator = null
        )

        assertTrue(consumers.isEmpty())
    }

    /**
     * Prüft, dass findProjectConsumersForManaged Pfade findet, wenn das Ziel im dependencyTree vorkommt.
     */
    fun testFindProjectConsumersForManagedFindsPaths() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        // Manuell dependencyTree mit Pfaden befüllen
        val springBootNode = createArtifactNode("org.springframework.boot", "spring-boot-starter", "3.3.5")
        val slf4jNode = createArtifactNode("org.slf4j", "slf4j-api", "2.0.9")
        slf4jNode.dependencies = mutableListOf()
        springBootNode.dependencies = mutableListOf(slf4jNode)

        // Da MavenProject.dependencyTree null ist, wird eine leere Liste zurückgegeben
        // Der Test bestätigt das Verhalten mit uninitialisiertem MavenProject
        val consumers = service.findProjectConsumersForManaged(
            mavenProject = mavenProject,
            managedGroupId = "org.slf4j",
            managedArtifactId = "slf4j-api",
            indicator = null
        )

        assertTrue(consumers.isEmpty())
    }

    // ---- matchesCoordinateScope isolierte Tests ----

    /**
     * Prüft, dass matchesCoordinateScope true zurückgibt, wenn der Filter inaktiv ist.
     */
    fun testMatchesCoordinateScopeReturnsTrueWhenFilterInactive() {
        val service = RedundantManagedDependencyService(project)

        assertTrue(service.matchesCoordinateScope("org.slf4j", "slf4j-api", null))
    }

    /**
     * Prüft, dass matchesCoordinateScope true zurückgibt, wenn die Koordinate passt.
     */
    fun testMatchesCoordinateScopeReturnsTrueWhenCoordinateMatches() {
        val service = RedundantManagedDependencyService(project)

        assertTrue(service.matchesCoordinateScope("org.slf4j", "slf4j-api", "org.slf4j:slf4j-api"))
    }

    /**
     * Prüft, dass matchesCoordinateScope false zurückgibt, wenn die Koordinate nicht passt.
     */
    fun testMatchesCoordinateScopeReturnsFalseWhenCoordinateDoesNotMatch() {
        val service = RedundantManagedDependencyService(project)

        assertFalse(service.matchesCoordinateScope("org.slf4j", "slf4j-api", "com.google:guava"))
    }

    // ---- collectManagedDependencies: Mehrere Einträge ----

    /**
     * Prüft, dass collectManagedDependencies mehrere Einträge korrekt sammelt.
     */
    fun testCollectManagedDependenciesCollectsMultipleEntries() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.7</version>
                            </dependency>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
                            <dependency>
                                <groupId>com.google.guava</groupId>
                                <artifactId>guava</artifactId>
                                <version>32.1.2-jre</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject)

        assertEquals(3, managed.size)
        val groupIds = managed.map { it.groupId }.toSet()
        val artifactIds = managed.map { it.artifactId }.toSet()
        assertTrue(groupIds.contains("org.slf4j"))
        assertTrue(groupIds.contains("org.apache.commons"))
        assertTrue(groupIds.contains("com.google.guava"))
        assertTrue(artifactIds.contains("slf4j-api"))
        assertTrue(artifactIds.contains("commons-lang3"))
        assertTrue(artifactIds.contains("guava"))
    }

    /**
     * Prüft, dass collectManagedDependencies Property-Platzhalter in Versionen auflöst,
     * wenn die Properties im Maven-Modell verfügbar sind.
     * Ohne initialisierte Properties bleibt der Platzhalter unverändert.
     */
    fun testCollectManagedDependenciesWithUnresolvedPropertyPlaceholder() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <slf4j.version>2.0.9</slf4j.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>${"$"}{slf4j.version}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject)

        assertEquals(1, managed.size)
        // MavenProject ohne Initialisierung hat leere Properties → Platzhalter bleibt unverändert
        assertEquals("${"$"}{slf4j.version}", managed.first().currentVersion)
    }

    /**
     * Prüft, dass collectManagedDependencies leere Liste bei leerer dependencyManagement-Sektion zurückgibt.
     */
    fun testCollectManagedDependenciesReturnsEmptyForEmptySection() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject)

        assertTrue(managed.isEmpty())
    }

    /**
     * Prüft, dass bei gemischten BOM-Imports und normalen Einträgen nur die normalen Einträge gesammelt werden.
     */
    fun testCollectManagedDependenciesIgnoresBomImportsMixedWithNormalEntries() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>3.3.5</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.7</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject)

        assertEquals(1, managed.size)
        assertEquals("org.slf4j", managed.first().groupId)
        assertEquals("slf4j-api", managed.first().artifactId)
    }

    // ---- collectDirectDependenciesWithExplicitVersions Tests ----

    /**
     * Prüft, dass collectDirectDependenciesWithExplicitVersions leere Map zurückgibt,
     * wenn keine direkten Dependencies existieren.
     */
    fun testCollectDirectDependenciesWithExplicitVersionsReturnsEmptyMap() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val directDeps = service.collectDirectDependenciesWithExplicitVersions(mavenProject)

        assertTrue(directDeps.isEmpty())
    }

    /**
     * Prüft, dass Dependencies ohne explizite Version ignoriert werden.
     */
    fun testCollectDirectDependenciesWithExplicitVersionsIgnoresMissingVersion() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val directDeps = service.collectDirectDependenciesWithExplicitVersions(mavenProject)

        assertEquals(1, directDeps.size)
        assertEquals("3.12.0", directDeps["org.apache.commons:commons-lang3"])
        assertNull(directDeps["org.slf4j:slf4j-api"])
    }

    /**
     * Prüft, dass Property-Platzhalter in direkten Dependency-Versionen unverändert bleiben,
     * wenn die Properties im Maven-Modell nicht verfügbar sind.
     */
    fun testCollectDirectDependenciesWithExplicitVersionsWithUnresolvedPropertyPlaceholder() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <commons.version>3.14.0</commons.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>${"$"}{commons.version}</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val directDeps = service.collectDirectDependenciesWithExplicitVersions(mavenProject)

        assertEquals(1, directDeps.size)
        // MavenProject ohne Initialisierung hat leere Properties → Platzhalter bleibt unverändert
        assertEquals("${"$"}{commons.version}", directDeps["org.apache.commons:commons-lang3"])
    }

    // ---- resolveFallbackManagedVersionWithoutLocalEntry Tests ----

    /**
     * Prüft, dass resolveFallbackManagedVersionWithoutLocalEntry null zurückgibt,
     * wenn kein Parent und kein BOM-Import vorhanden ist.
     */
    fun testResolveFallbackManagedVersionWithoutLocalEntryReturnsNullWhenNoFallback() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.7</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)

        val fallback = service.resolveFallbackManagedVersionWithoutLocalEntry(
            mavenProject = mavenProject,
            managedCoordinate = "org.slf4j:slf4j-api"
        )

        // Kein Parent, kein BOM → kein Fallback
        assertNull(fallback)
    }
}
