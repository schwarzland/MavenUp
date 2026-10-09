package de.schwarzland.mavenup.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unittests für [TemporaryDependencyTreeResolver].
 */
class TemporaryDependencyTreeResolverTest {

    /**
     * Prüft, dass fehlende und ungültige POM-Daten als Diagnose erhalten bleiben und zurücksetzbar sind.
     */
    @Test
    fun testResolutionIssuesTrackMissingAndInvalidPoms() {
        val resolver = TemporaryDependencyTreeResolver { _, artifactId, _ ->
            if (artifactId == "invalid") "<project>" else null
        }

        assertTrue(resolver.resolveEffectivePom("g", "missing", "1") == null)
        assertTrue(resolver.resolveEffectivePom("g", "invalid", "1") == null)

        assertEquals(
            setOf("POM: g:missing:1", "Invalid POM: g:invalid:1"),
            resolver.resolutionIssues
        )

        resolver.resetResolutionIssues()

        assertTrue(resolver.resolutionIssues.isEmpty())
    }

    @Test
    fun testResolveSimplePomWithProperties() {
        val pomXml = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
                <properties>
                    <jackson.version>2.15.2</jackson.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>${'$'}{jackson.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            if (g == "com.example" && a == "my-app" && v == "1.0.0") pomXml else null
        }

        val tree = resolver.resolveDependencyTree("com.example", "my-app", "1.0.0")
        assertNotNull(tree)
        assertEquals(1, tree.dependencies.size)
        assertEquals("com.fasterxml.jackson.core", tree.dependencies[0].coordinate.groupId)
        assertEquals("jackson-databind", tree.dependencies[0].coordinate.artifactId)
        assertEquals("2.15.2", tree.dependencies[0].coordinate.version)
    }

    @Test
    fun testResolveParentPomHierarchyAndDependencyManagement() {
        val parentPomXml = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>parent-pom</artifactId>
                <version>2.0.0</version>
                <properties>
                    <spring.version>6.1.0</spring.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework</groupId>
                            <artifactId>spring-core</artifactId>
                            <version>${'$'}{spring.version}</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val childPomXml = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>2.0.0</version>
                </parent>
                <artifactId>child-app</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework</groupId>
                        <artifactId>spring-core</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            when ("$g:$a:$v") {
                "com.example:parent-pom:2.0.0" -> parentPomXml
                "com.example:child-app:2.0.0" -> childPomXml
                else -> null
            }
        }

        val tree = resolver.resolveDependencyTree("com.example", "child-app", "2.0.0")
        assertNotNull(tree)
        assertEquals(1, tree.dependencies.size)
        assertEquals("org.springframework", tree.dependencies[0].coordinate.groupId)
        assertEquals("spring-core", tree.dependencies[0].coordinate.artifactId)
        assertEquals("6.1.0", tree.dependencies[0].coordinate.version)

        val depMgmt = resolver.resolveEffectiveDependencyManagement("com.example", "child-app", "2.0.0")
        assertEquals("6.1.0", depMgmt["org.springframework:spring-core"])
    }

    @Test
    fun testResolveTransitiveDependencies() {
        val directPom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>direct-lib</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>transitive-lib</artifactId>
                        <version>3.5.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val transitivePom = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>transitive-lib</artifactId>
                <version>3.5.0</version>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            when ("$g:$a:$v") {
                "com.example:direct-lib:1.0.0" -> directPom
                "com.example:transitive-lib:3.5.0" -> transitivePom
                else -> null
            }
        }

        val transitives = resolver.resolveTransitiveDependencies("com.example", "direct-lib", "1.0.0")
        assertEquals(1, transitives.size)
        assertEquals("3.5.0", transitives["com.example:transitive-lib"])
    }

    @Test
    fun testCycleDetection() {
        val pomA = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib-a</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib-b</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val pomB = """
            <project>
                <groupId>com.example</groupId>
                <artifactId>lib-b</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val resolver = TemporaryDependencyTreeResolver { g, a, v ->
            when ("$g:$a:$v") {
                "com.example:lib-a:1.0.0" -> pomA
                "com.example:lib-b:1.0.0" -> pomB
                else -> null
            }
        }

        val tree = resolver.resolveDependencyTree("com.example", "lib-a", "1.0.0")
        assertNotNull(tree)
        assertEquals(1, tree.dependencies.size)
        assertEquals("lib-b", tree.dependencies[0].coordinate.artifactId)
        assertEquals(1, tree.dependencies[0].dependencies.size)
        assertEquals("lib-a", tree.dependencies[0].dependencies[0].coordinate.artifactId)
        // Lib-a within lib-b should stop recursion because of cycle detection
        assertTrue(tree.dependencies[0].dependencies[0].dependencies.isEmpty())
    }
}
