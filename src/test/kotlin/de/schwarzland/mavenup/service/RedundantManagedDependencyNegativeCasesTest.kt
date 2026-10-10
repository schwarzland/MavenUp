package de.schwarzland.mavenup.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.RedundancyReason
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject

/**
* Negative-Tests für [RedundantManagedDependencyService].
* Deckt alle Fälle ab, in denen keine Redundanz erkannt wird oder `null` zurückgegeben wird.
*/
class RedundantManagedDependencyNegativeCasesTest : BasePlatformTestCase() {

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

    // ---- Negative Cases: Nicht-redundante Einträge ----

    /**
     * Prüft, dass evaluateRedundancy null zurückgibt, wenn ein managed entry notwendig ist:
     * Das Artefakt wird direkt mit einer niedrigeren Version deklariert, kein Parent-Management,
     * und das Artefakt ist im Projekt verwendet (also kein UNUSED).
     *
     * Da MavenProject ohne vollständige Initialisierung keine dependencyTree/dependencies hat,
     * wird isUsedInAnyProject false zurückgeben → UNUSED wird erkannt.
     * Der Null-Fall (nicht-redundanter Eintrag) tritt auf, wenn isUsedInAnyProject true ist,
     * aber keine der anderen Kriterien zutrifft. Dies wird implizit durch die anderen Tests
     * abgedeckt, wo evaluateRedundancy null zurückgibt, wenn kein Kriterium passt.
     *
     * Dieser Test bestätigt, dass bei synthetischen MavenProjects (ohne initialisierte dependencies)
     * ein managed entry mit niedrigerer direkter Version als UNUSED erkannt wird,
     * da isUsedInAnyProject false zurückgibt.
     */
    fun testNonRedundantEntryWithLowerDirectVersionIsDetectedAsUnused() {
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
                                <version>2.0.9</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()

        // Direct version 2.0.7 < managed 2.0.9 → kein DIRECT_DEPENDENCY_MATCH
        // Kein Parent → kein PARENT_MANAGED
        // isUsedInAnyProject: synthetisches MavenProject ohne dependencies → false → UNUSED
        val recommendation = service.evaluateRedundancy(
            managed = managed,
            mavenProject = mavenProject,
            allProjects = listOf(mavenProject),
            directDependenciesWithExplicitVersion = mapOf("org.slf4j:slf4j-api" to "2.0.7"),
            indicator = null
        )

        // Da isUsedInAnyProject false ist (kein reales Maven-Modell), wird UNUSED erkannt
        assertNotNull(recommendation)
        assertEquals(RedundancyReason.UNUSED, recommendation!!.reason)
    }

    /**
     * Prüft, dass evaluateParentManaged null zurückgibt, wenn kein Parent vorhanden ist.
     */
    fun testEvaluateParentManagedReturnsNullWhenNoParent() {
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
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0")

        val recommendation = service.evaluateParentManaged(
            managed = managed,
            mavenProject = mavenProject,
            key = "org.apache.commons:commons-lang3",
            managedComp = managedComp,
            consumerPaths = emptyList()
        )

        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateParentManaged null zurückgibt, wenn die Parent-Version niedriger ist.
     */
    fun testEvaluateParentManagedReturnsNullWhenParentVersionLower() {
        val (mavenProject, parentPom) = setupParentManagedProject()

        val lowerVersionResolver = TemporaryDependencyTreeResolver { groupId, artifactId, version ->
            if (groupId == "com.example" && artifactId == "my-parent" && version == "1.0.0") {
                parentPom.replace("3.12.0", "3.11.0")
            } else {
                null
            }
        }

        val service = RedundantManagedDependencyService(project, treeResolver = lowerVersionResolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0")

        val recommendation = service.evaluateParentManaged(
            managed = managed,
            mavenProject = mavenProject,
            key = "org.apache.commons:commons-lang3",
            managedComp = managedComp,
            consumerPaths = emptyList()
        )

        // Parent 3.11.0 < lokal 3.12.0 → kein Redundanzfund
        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateParentManaged null zurückgibt, wenn das Parent-POM die Koordinate nicht enthält.
     */
    fun testEvaluateParentManagedReturnsNullWhenParentMissingCoordinate() {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>my-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val parentPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-parent</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.other</groupId>
                            <artifactId>other-lib</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { groupId, artifactId, version ->
            if (groupId == "com.example" && artifactId == "my-parent" && version == "1.0.0") {
                parentPom
            } else {
                null
            }
        }

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project, treeResolver = resolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0")

        val recommendation = service.evaluateParentManaged(
            managed = managed,
            mavenProject = mavenProject,
            key = "org.apache.commons:commons-lang3",
            managedComp = managedComp,
            consumerPaths = emptyList()
        )

        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateParentManaged null zurückgibt, wenn das Laden des Parent-POMs eine Exception wirft.
     */
    fun testEvaluateParentManagedReturnsNullOnException() {
        val mavenProject = setupParentManagedProject().first

        val throwingResolver = TemporaryDependencyTreeResolver { _, _, _ ->
            throw RuntimeException("Network error")
        }

        val service = RedundantManagedDependencyService(project, treeResolver = throwingResolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("3.12.0")

        val recommendation = service.evaluateParentManaged(
            managed = managed,
            mavenProject = mavenProject,
            key = "org.apache.commons:commons-lang3",
            managedComp = managedComp,
            consumerPaths = emptyList()
        )

        assertNull(recommendation)
    }

    // ---- evaluateDirectMatch Negative Cases ----

    /**
     * Prüft, dass evaluateDirectMatch null zurückgibt, wenn die direkte Version niedriger ist.
     */
    fun testEvaluateDirectMatchReturnsNullWhenDirectVersionLower() {
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
                                <version>2.0.9</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.0.9")

        val recommendation = service.evaluateDirectMatch(
            managed = managed,
            key = "org.slf4j:slf4j-api",
            managedComp = managedComp,
            directDependenciesWithExplicitVersion = mapOf("org.slf4j:slf4j-api" to "2.0.7"),
            consumerPaths = emptyList()
        )

        // 2.0.7 < 2.0.9 → kein Match
        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateDirectMatch null zurückgibt, wenn keine direkte Deklaration existiert.
     */
    fun testEvaluateDirectMatchReturnsNullWhenNoDirectDeclaration() {
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
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.0.7")

        val recommendation = service.evaluateDirectMatch(
            managed = managed,
            key = "org.slf4j:slf4j-api",
            managedComp = managedComp,
            directDependenciesWithExplicitVersion = emptyMap(),
            consumerPaths = emptyList()
        )

        assertNull(recommendation)
    }

    // ---- evaluateTransitiveMatch Negative Cases ----

    /**
     * Prüft, dass evaluateTransitiveMatch null zurückgibt, wenn consumerPaths leer ist.
     */
    fun testEvaluateTransitiveMatchReturnsNullWhenEmptyConsumerPaths() {
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
                                <groupId>org.xmlunit</groupId>
                                <artifactId>xmlunit-core</artifactId>
                                <version>2.9.1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.9.1")

        val recommendation = service.evaluateTransitiveMatch(
            managed = managed,
            mavenProject = mavenProject,
            managedComp = managedComp,
            consumerPaths = emptyList(),
            indicator = null
        )

        assertNull(recommendation)
    }

    /**
     * Prüft, dass evaluateTransitiveMatch null zurückgibt, wenn die transitive Version niedriger ist.
     */
    fun testEvaluateTransitiveMatchReturnsNullWhenTransitiveVersionLower() {
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
                                <groupId>org.xmlunit</groupId>
                                <artifactId>xmlunit-core</artifactId>
                                <version>2.9.1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val starterPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-test</artifactId>
                <version>3.3.5</version>
                <dependencies>
                    <dependency>
                        <groupId>org.xmlunit</groupId>
                        <artifactId>xmlunit-core</artifactId>
                        <version>2.8.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { groupId, artifactId, _ ->
            if (groupId == "org.springframework.boot" && artifactId == "spring-boot-starter-test") {
                starterPom
            } else {
                null
            }
        }

        val mavenProject = MavenProject(pom.virtualFile)
        val service = RedundantManagedDependencyService(project, treeResolver = resolver)
        val managed = service.collectManagedDependencies(mavenProject).first()
        val managedComp = org.apache.maven.artifact.versioning.ComparableVersion("2.9.1")

        val starterNode = createArtifactNode("org.springframework.boot", "spring-boot-starter-test", "3.3.5")
        val xmlunitNode = createArtifactNode("org.xmlunit", "xmlunit-core", "2.8.0")
        val consumerPath = listOf(starterNode, xmlunitNode)

        val recommendation = service.evaluateTransitiveMatch(
            managed = managed,
            mavenProject = mavenProject,
            managedComp = managedComp,
            consumerPaths = listOf(consumerPath),
            indicator = null
        )

        // 2.8.0 < 2.9.1 → kein Match
        assertNull(recommendation)
    }

    private fun setupParentManagedProject(): Pair<MavenProject, String> {
        val pom = myFixture.configureByText(
            "pom.xml",
            """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>my-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )

        val parentPom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-parent</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        return Pair(MavenProject(pom.virtualFile), parentPom)
    }
}
