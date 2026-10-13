package de.schwarzland.mavenup.service

import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.DependencyHierarchyNode
import de.schwarzland.mavenup.model.DependencyHierarchyNodeType
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.model.MavenArtifactState

/** Regressionstests für wiederholte Maven-Artefakte und pfadlokale Zyklenerkennung. */
class DependencyHierarchyPathTest : BasePlatformTestCase() {

    /** Prüft beide BeanUtils-Versionen hinter einem zuvor als Blatt besuchten MockServer-Artefakt. */
    fun testRepeatedLeafDoesNotHideBeanutilsHierarchy() {
        val oldBeanutils = node("commons-beanutils", "commons-beanutils", "1.8.3")
        val beanutils = node("commons-beanutils", "commons-beanutils", "1.9.4")
        val digester = node("org.apache.commons", "commons-digester3", "3.2", oldBeanutils)
        val velocity = node("org.apache.velocity.tools", "velocity-tools-generic", "3.1", beanutils, digester)
        val coreLeaf = node("org.mock-server", "mockserver-core", "5.15.0")
        val core = node("org.mock-server", "mockserver-core", "5.15.0", velocity)
        val client = node("org.mock-server", "mockserver-client-java", "5.15.0", coreLeaf)
        val root = node("org.mock-server", "mockserver-netty", "5.15.0", client, core)
        assertEquals(coreLeaf, core)
        assertNotSame(coreLeaf, core)

        val service = DependencyHierarchyService(project)
        val paths = mutableListOf<List<MavenArtifactNode>>()
        service.findPathsToTarget(root, "commons-beanutils", "commons-beanutils", emptyList(), paths)

        assertEquals(listOf("1.9.4", "1.8.3"), paths.map { it.last().artifact.version })
        assertEquals(listOf(4, 5), paths.map { it.size })
        assertTrue(paths.all { it[1] === core })

        val pom = myFixture.configureByText("pom.xml", "<project/>") as XmlFile
        val hierarchy = DependencyHierarchyNode(DependencyHierarchyNodeType.PROJECT, "example", "app")
        paths.forEach { service.attachPathToHierarchy(hierarchy, it, pom.virtualFile) }
        val velocityHierarchy = hierarchy.children.single().children.single().children.single()
        assertEquals("velocity-tools-generic", velocityHierarchy.artifactId)
        assertEquals("1.9.4", velocityHierarchy.children.first().version)
        assertEquals("commons-digester3", velocityHierarchy.children.last().artifactId)
        assertEquals("1.8.3", velocityHierarchy.children.last().children.single().version)
    }

    /** Prüft unterschiedliche Instanzen derselben Koordinate innerhalb eines einzigen Pfads. */
    fun testEqualAncestorDoesNotHideDistinctNodeChildren() {
        val target = node("example", "target", "1")
        val repeated = node("example", "repeated", "1", target)
        val root = node("example", "repeated", "1", repeated)
        val paths = mutableListOf<List<MavenArtifactNode>>()

        DependencyHierarchyService(project).findPathsToTarget(root, "example", "target", emptyList(), paths)

        assertEquals(1, paths.size)
        assertEquals(3, paths.single().size)
        assertSame(repeated, paths.single()[1])
    }

    /** Prüft, dass ein gemeinsam genutzter Teilbaum über beide Geschwisterpfade gefunden wird. */
    fun testSharedSubtreeIsVisitedForEachPath() {
        val target = node("example", "target", "1")
        val shared = node("example", "shared", "1", target)
        val first = node("example", "first", "1", shared)
        val second = node("example", "second", "1", shared)
        val root = node("example", "root", "1", first, second)
        val paths = mutableListOf<List<MavenArtifactNode>>()

        DependencyHierarchyService(project).findPathsToTarget(root, "example", "target", emptyList(), paths)

        assertEquals(listOf("first", "second"), paths.map { it[1].artifact.artifactId })
    }

    /** Prüft Abbruch bei einem echten Zyklus sowie die weitere Suche nach einem erreichbaren Ziel. */
    fun testCycleDoesNotPreventOtherTargetPaths() {
        val target = node("example", "target", "1")
        val branch = node("example", "branch", "1")
        val root = node("example", "root", "1", branch)
        branch.dependencies = listOf(root, target)
        val service = DependencyHierarchyService(project)
        val paths = mutableListOf<List<MavenArtifactNode>>()

        service.findPathsToTarget(root, "example", "target", emptyList(), paths)
        assertEquals(1, paths.size)
        assertEquals(3, paths.single().size)

        val missingPaths = mutableListOf<List<MavenArtifactNode>>()
        service.findPathsToTarget(root, "example", "missing", emptyList(), missingPaths)
        assertTrue(missingPaths.isEmpty())
    }

    /** Prüft ein direkt getroffenes Ziel und ein Blatt ohne passende Koordinate. */
    fun testTargetRootAndUnmatchedLeaf() {
        val root = node("example", "target", "1")
        val service = DependencyHierarchyService(project)
        val paths = mutableListOf<List<MavenArtifactNode>>()
        service.findPathsToTarget(root, "example", "target", emptyList(), paths)
        assertSame(root, paths.single().single())

        val missingPaths = mutableListOf<List<MavenArtifactNode>>()
        service.findPathsToTarget(root, "", "", emptyList(), missingPaths)
        assertTrue(missingPaths.isEmpty())
    }

    /** Erzeugt einen Maven-Knoten mit explizitem Kindbestand über die öffentliche Maven-API. */
    private fun node(
        groupId: String,
        artifactId: String,
        version: String,
        vararg children: MavenArtifactNode
    ): MavenArtifactNode {
        val artifact = MavenArtifact(
            groupId, artifactId, version, null, "jar", null, "compile",
            false, "jar", null, null, true, false
        )
        return MavenArtifactNode(null, artifact, MavenArtifactState.ADDED, null, null, null, null).apply {
            dependencies = children.toList()
        }
    }
}
