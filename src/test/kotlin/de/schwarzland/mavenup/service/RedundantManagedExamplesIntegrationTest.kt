package de.schwarzland.mavenup.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import de.schwarzland.mavenup.model.TemporaryDependencyNode
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProject
import java.io.File
import java.lang.reflect.Field
import java.util.Properties
import java.util.regex.Pattern

/**
 * End-to-end integration tests executing redundancy analysis directly against all 15 reference
 * example projects defined in `src/test/resources/redundant-managed-integrations-test/`.
 */
class RedundantManagedExamplesIntegrationTest : BasePlatformTestCase() {

    private data class ScenarioContext(
        val rootProject: MavenProject,
        val allProjects: List<MavenProject>,
        val projectByArtifactId: Map<String, MavenProject>,
        val service: RedundantManagedDependencyService
    )

    /**
     * Loads an example scenario from `src/test/resources/redundant-managed-integrations-test/<scenarioName>` into the test fixture
     * and sets up a [RedundantManagedDependencyService] with offline POM/BOM fetchers.
     */
    private fun loadScenario(
        scenarioName: String,
        additionalPoms: Map<String, String> = emptyMap()
    ): ScenarioContext {
        val scenarioDir = File("src/test/resources/redundant-managed-integrations-test", scenarioName)
        assertTrue("Scenario directory must exist: ${scenarioDir.absolutePath}", scenarioDir.exists())

        val pomFilesByCoordinate = mutableMapOf<String, String>()
        val allProjects = mutableListOf<MavenProject>()
        val projectByArtifactId = mutableMapOf<String, MavenProject>()

        scenarioDir.walkTopDown().filter { it.isFile && it.name == "pom.xml" }.forEach { file ->
            val relativePath = file.relativeTo(scenarioDir).path.replace(File.separatorChar, '/')
            val content = file.readText()
            val vFile = myFixture.addFileToProject("$scenarioName/$relativePath", content).virtualFile
            val mavenProject = MavenProject(vFile)
            allProjects.add(mavenProject)

            val coordinate = extractCoordinateFromPom(content)
            if (coordinate != null) {
                pomFilesByCoordinate[coordinate] = content
                val artifactId = coordinate.split(":")[1]
                projectByArtifactId[artifactId] = mavenProject
            }
        }

        val treeResolver = TemporaryDependencyTreeResolver(project) { g, a, v ->
            val key = "$g:$a:$v"
            pomFilesByCoordinate[key]
                ?: additionalPoms[key]
                ?: defaultExternalPoms[key]
        }

        // Populate MavenProject models (mavenId, parentId, properties, dependencies, dependencyTree)
        allProjects.forEach { mavenProj ->
            populateMavenProjectModel(mavenProj, treeResolver)
        }

        val rootProject = projectByArtifactId[scenarioName]
            ?: allProjects.firstOrNull { it.file.parent.name == scenarioName }
            ?: allProjects.first()

        val service = RedundantManagedDependencyService(project, treeResolver = treeResolver)
        return ScenarioContext(
            rootProject = rootProject,
            allProjects = allProjects,
            projectByArtifactId = projectByArtifactId,
            service = service
        )
    }

    private fun populateMavenProjectModel(mavenProj: MavenProject, treeResolver: TemporaryDependencyTreeResolver) {
        val properties = Properties()
        var mavenId: MavenId? = null
        var parentId: MavenId? = null
        val directArtifacts = mutableListOf<MavenArtifact>()

        ApplicationManager.getApplication().runReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(mavenProj.file) as? XmlFile ?: return@runReadAction
            val rootTag = psiFile.document?.rootTag ?: return@runReadAction

            extractProperties(rootTag, properties)
            parentId = extractParentId(rootTag)
            mavenId = extractMavenId(rootTag, parentId)
            directArtifacts.addAll(extractDirectArtifacts(rootTag, properties))
        }

        val rootNodes = buildDependencyTreeNodes(directArtifacts, mavenId, treeResolver)

        val stateField = MavenProject::class.java.getDeclaredField("myState").apply { isAccessible = true }
        val state = stateField.get(mavenProj)
        if (state != null) {
            setField(state, "mavenId", mavenId)
            setField(state, "parentId", parentId)
            setField(state, "properties", properties)
            setField(state, "dependencies", directArtifacts)
            setField(state, "dependencyTree", rootNodes)
        }
    }

    private fun extractProperties(rootTag: com.intellij.psi.xml.XmlTag, properties: Properties) {
        rootTag.findFirstSubTag("properties")?.subTags?.forEach { propTag ->
            properties.setProperty(propTag.name, propTag.value.text.trim())
        }
    }

    private fun extractParentId(rootTag: com.intellij.psi.xml.XmlTag): MavenId? {
        val parentTag = rootTag.findFirstSubTag("parent") ?: return null
        val pGroup = parentTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
        val pArtifact = parentTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
        val pVersion = parentTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
        return if (pGroup.isNotEmpty() && pArtifact.isNotEmpty() && pVersion.isNotEmpty()) {
            MavenId(pGroup, pArtifact, pVersion)
        } else null
    }

    private fun extractMavenId(rootTag: com.intellij.psi.xml.XmlTag, parentId: MavenId?): MavenId? {
        val pGroup = parentId?.groupId.orEmpty()
        val pVersion = parentId?.version.orEmpty()
        val g = rootTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty().ifEmpty { pGroup }
        val a = rootTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
        val v = rootTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty().ifEmpty { pVersion }
        return if (g.isNotEmpty() && a.isNotEmpty() && v.isNotEmpty()) {
            MavenId(g, a, v)
        } else null
    }

    private fun extractDirectArtifacts(
        rootTag: com.intellij.psi.xml.XmlTag,
        properties: Properties
    ): List<MavenArtifact> {
        val directArtifacts = mutableListOf<MavenArtifact>()
        rootTag.findFirstSubTag("dependencies")?.findSubTags("dependency")?.forEach { depTag ->
            val dg = depTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            val da = depTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            var dv = depTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
            if (dv.startsWith("\${") && dv.endsWith("}")) {
                val propName = dv.removeSurrounding("\${", "}").trim()
                dv = properties.getProperty(propName, dv)
            }
            if (dg.isNotEmpty() && da.isNotEmpty()) {
                directArtifacts.add(createArtifact(dg, da, dv))
            }
        }
        return directArtifacts
    }

    private fun buildDependencyTreeNodes(
        directArtifacts: List<MavenArtifact>,
        mavenId: MavenId?,
        treeResolver: TemporaryDependencyTreeResolver
    ): List<MavenArtifactNode> {
        val rootNodes = mutableListOf<MavenArtifactNode>()
        directArtifacts.forEach { directDep ->
            val version = if (directDep.version.isNullOrEmpty()) {
                val effectiveMgmt = if (mavenId != null) {
                    treeResolver.resolveEffectiveDependencyManagement(
                        mavenId.groupId.orEmpty(),
                        mavenId.artifactId.orEmpty(),
                        mavenId.version.orEmpty()
                    )
                } else emptyMap()
                effectiveMgmt["${directDep.groupId}:${directDep.artifactId}"].orEmpty()
            } else {
                directDep.version
            }

            val treeNode = if (version.isNotEmpty()) {
                treeResolver.resolveDependencyTree(directDep.groupId, directDep.artifactId, version)
            } else {
                null
            }

            val artifactNode = if (treeNode != null) {
                toMavenArtifactNode(treeNode)
            } else {
                val node = MavenArtifactNode(null, directDep, null, null, null, null, null)
                setField(node, "myDependencies", emptyList<MavenArtifactNode>())
                node
            }
            rootNodes.add(artifactNode)
        }
        return rootNodes
    }

    private fun toMavenArtifactNode(tempNode: TemporaryDependencyNode): MavenArtifactNode {
        val artifact = createArtifact(tempNode.coordinate.groupId, tempNode.coordinate.artifactId, tempNode.coordinate.version)
        val node = MavenArtifactNode(null, artifact, null, null, null, null, null)
        val childNodes = tempNode.dependencies.map { toMavenArtifactNode(it) }
        setField(node, "myDependencies", childNodes)
        return node
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        val allFields = mutableListOf<String>()
        var c = target.javaClass as Class<*>?
        while (c != null) {
            allFields.addAll(c.declaredFields.map { it.name })
            c = c.superclass
        }
        println("Could not find field $fieldName on ${target.javaClass}. Available: $allFields")
    }

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
            true,
            false
        )
    }

    private fun extractCoordinateFromPom(content: String): String? {
        val rootWithoutParent = content.replace(Regex("<parent>[\\s\\S]*?</parent>"), "")
        val groupIdMatcher = Pattern.compile("<groupId>([^<]+)</groupId>").matcher(rootWithoutParent)
        val artifactIdMatcher = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(rootWithoutParent)
        val versionMatcher = Pattern.compile("<version>([^<]+)</version>").matcher(rootWithoutParent)

        val artifactId = if (artifactIdMatcher.find()) artifactIdMatcher.group(1).trim() else return null
        val groupId = if (groupIdMatcher.find()) {
            groupIdMatcher.group(1).trim()
        } else {
            val parentGroupMatcher = Pattern.compile("<parent>[\\s\\S]*?<groupId>([^<]+)</groupId>[\\s\\S]*?</parent>").matcher(content)
            if (parentGroupMatcher.find()) parentGroupMatcher.group(1).trim() else "de.schwarzland.mavenup.examples"
        }
        val version = if (versionMatcher.find()) {
            versionMatcher.group(1).trim()
        } else {
            val parentVerMatcher = Pattern.compile("<parent>[\\s\\S]*?<version>([^<]+)</version>[\\s\\S]*?</parent>").matcher(content)
            if (parentVerMatcher.find()) parentVerMatcher.group(1).trim() else "1.0.0"
        }

        return "$groupId:$artifactId:$version"
    }

    private fun findRecommendations(
        context: ScenarioContext,
        targetArtifactId: String? = null
    ): List<RedundantManagedDependencyRecommendation> {
        val target = if (targetArtifactId != null) {
            context.projectByArtifactId[targetArtifactId]
                ?: error("Project $targetArtifactId not found in scenario. Available: ${context.projectByArtifactId.keys}")
        } else {
            context.rootProject
        }

        return context.service.findRedundantForProject(
            mavenProject = target,
            allProjects = context.allProjects,
            managedCoordinate = null,
            indicator = null
        )
    }

    companion object {
        private val defaultExternalPoms = mapOf(
            "com.fasterxml.jackson.core:jackson-databind:2.15.2" to """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.15.2</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.fasterxml.jackson.core</groupId>
                            <artifactId>jackson-core</artifactId>
                            <version>2.15.2</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent(),
            "com.fasterxml.jackson.core:jackson-core:2.15.2" to """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-core</artifactId>
                    <version>2.15.2</version>
                </project>
            """.trimIndent(),
            "org.springframework.boot:spring-boot-dependencies:3.2.0" to """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-dependencies</artifactId>
                    <version>3.2.0</version>
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework</groupId>
                                <artifactId>spring-web</artifactId>
                                <version>6.1.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
            """.trimIndent()
        )
    }

    fun testScenario01_ParentManagedSameVersion() {
        val context = loadScenario("01-parent-managed-same-version")

        val parentRecs = findRecommendations(context, targetArtifactId = "parent-managed-same-version")
        assertTrue("Parent POM should have no redundant managed dependencies", parentRecs.isEmpty())

        val childRecs = findRecommendations(context, targetArtifactId = "child")
        assertEquals(1, childRecs.size)
        val rec = childRecs.first()
        assertEquals("org.apache.commons", rec.groupId)
        assertEquals("commons-lang3", rec.artifactId)
        assertEquals("3.12.0", rec.currentVersion)
        assertEquals("3.12.0", rec.providedVersion)
        assertEquals(RedundancyReason.PARENT_MANAGED, rec.reason)
    }

    fun testScenario02_ParentManagedHigherVersion() {
        val context = loadScenario("02-parent-managed-higher-version")

        val parentRecs = findRecommendations(context, targetArtifactId = "parent-managed-higher-version")
        assertTrue("Parent POM should have no redundant managed dependencies", parentRecs.isEmpty())

        val childRecs = findRecommendations(context, targetArtifactId = "child")
        assertEquals(1, childRecs.size)
        val rec = childRecs.first()
        assertEquals("org.apache.commons", rec.groupId)
        assertEquals("commons-lang3", rec.artifactId)
        assertEquals("3.12.0", rec.currentVersion)
        assertEquals("3.14.0", rec.providedVersion)
        assertEquals(RedundancyReason.PARENT_MANAGED, rec.reason)
    }

    fun testScenario03_DirectDependencyMatch() {
        val context = loadScenario("03-direct-dependency-match")

        val recs = findRecommendations(context)
        assertEquals(1, recs.size)
        val rec = recs.first()
        assertEquals("org.slf4j", rec.groupId)
        assertEquals("slf4j-api", rec.artifactId)
        assertEquals("2.0.9", rec.currentVersion)
        assertEquals("2.0.9", rec.providedVersion)
        assertEquals(RedundancyReason.DIRECT_DEPENDENCY_MATCH, rec.reason)
    }

    fun testScenario04_TransitiveMatch() {
        val context = loadScenario("04-transitive-match")

        val recs = findRecommendations(context)
        assertEquals(1, recs.size)
        val rec = recs.first()
        assertEquals("com.fasterxml.jackson.core", rec.groupId)
        assertEquals("jackson-core", rec.artifactId)
        assertEquals("2.15.0", rec.currentVersion)
        assertEquals("2.15.2", rec.providedVersion)
        assertEquals(RedundancyReason.TRANSITIVE_MATCH, rec.reason)
    }

    fun testScenario05_UnusedManaged() {
        val context = loadScenario("05-unused-managed")

        val recs = findRecommendations(context)
        assertEquals(1, recs.size)
        val rec = recs.first()
        assertEquals("org.apache.logging.log4j", rec.groupId)
        assertEquals("log4j-core", rec.artifactId)
        assertEquals("2.20.0", rec.currentVersion)
        assertNull(rec.providedVersion)
        assertEquals(RedundancyReason.UNUSED, rec.reason)
    }

    fun testScenario06_MultiModuleMixed() {
        val context = loadScenario("06-multi-module-mixed")

        val parentRecs = findRecommendations(context, targetArtifactId = "multi-module-mixed")
        assertTrue("Parent POM should have no redundant managed dependencies", parentRecs.isEmpty())

        val moduleARecs = findRecommendations(context, targetArtifactId = "module-a")
        assertEquals(1, moduleARecs.size)
        val recA = moduleARecs.first()
        assertEquals("org.slf4j", recA.groupId)
        assertEquals("slf4j-api", recA.artifactId)
        assertEquals("2.0.9", recA.currentVersion)
        assertEquals("2.0.9", recA.providedVersion)
        assertEquals(RedundancyReason.PARENT_MANAGED, recA.reason)

        val moduleBRecs = findRecommendations(context, targetArtifactId = "module-b")
        assertEquals(1, moduleBRecs.size)
        val recB = moduleBRecs.first()
        assertEquals("com.fasterxml.jackson.core", recB.groupId)
        assertEquals("jackson-core", recB.artifactId)
        assertEquals("2.15.0", recB.currentVersion)
        assertNull(recB.providedVersion)
        assertEquals(RedundancyReason.UNUSED, recB.reason)
    }

    fun testScenario07_MultiModuleBomImport() {
        val context = loadScenario("07-multi-module-bom-import")

        val parentRecs = findRecommendations(context, targetArtifactId = "multi-module-bom-import")
        assertTrue("Parent POM should have no redundant managed dependencies", parentRecs.isEmpty())

        val moduleARecs = findRecommendations(context, targetArtifactId = "module-a")
        assertTrue("Module A has no dependencyManagement", moduleARecs.isEmpty())

        val moduleBRecs = findRecommendations(context, targetArtifactId = "module-b")
        assertEquals(1, moduleBRecs.size)
        val recB = moduleBRecs.first()
        assertEquals("org.springframework", recB.groupId)
        assertEquals("spring-web", recB.artifactId)
        assertEquals("6.1.0", recB.currentVersion)
        assertEquals("6.1.0", recB.providedVersion)
        assertEquals(RedundancyReason.PARENT_MANAGED, recB.reason)
    }

    fun testScenario08_DirectDependencyHigherVersion() {
        val context = loadScenario("08-direct-dependency-higher-version")

        val recs = findRecommendations(context)
        assertEquals(1, recs.size)
        val rec = recs.first()
        assertEquals("org.slf4j", rec.groupId)
        assertEquals("slf4j-api", rec.artifactId)
        assertEquals("2.0.7", rec.currentVersion)
        assertEquals("2.0.9", rec.providedVersion)
        assertEquals(RedundancyReason.DIRECT_DEPENDENCY_MATCH, rec.reason)
    }

    fun testScenario09_TransitiveMatchSameVersion() {
        val context = loadScenario("09-transitive-match-same-version")

        val recs = findRecommendations(context)
        assertEquals(1, recs.size)
        val rec = recs.first()
        assertEquals("com.fasterxml.jackson.core", rec.groupId)
        assertEquals("jackson-core", rec.artifactId)
        assertEquals("2.15.2", rec.currentVersion)
        assertEquals("2.15.2", rec.providedVersion)
        assertEquals(RedundancyReason.TRANSITIVE_MATCH, rec.reason)
    }

    fun testScenario10_MultiTierGrandparentInheritance() {
        val context = loadScenario("10-multi-tier-grandparent-inheritance")

        val grandparentRecs = findRecommendations(context, targetArtifactId = "multi-tier-grandparent")
        assertTrue("Grandparent POM should have no redundant managed dependencies", grandparentRecs.isEmpty())

        val parentRecs = findRecommendations(context, targetArtifactId = "parent")
        assertTrue("Parent POM should have no redundant managed dependencies", parentRecs.isEmpty())

        val childRecs = findRecommendations(context, targetArtifactId = "child")
        assertEquals(1, childRecs.size)
        val rec = childRecs.first()
        assertEquals("org.apache.commons", rec.groupId)
        assertEquals("commons-lang3", rec.artifactId)
        assertEquals("3.12.0", rec.currentVersion)
        assertEquals("3.12.0", rec.providedVersion)
        assertEquals(RedundancyReason.PARENT_MANAGED, rec.reason)
    }

    fun testScenario11_PropertyManagedRedundancy() {
        val context = loadScenario("11-property-managed-redundancy")

        val recs = findRecommendations(context)
        assertEquals(1, recs.size)
        val rec = recs.first()
        assertEquals("org.apache.commons", rec.groupId)
        assertEquals("commons-lang3", rec.artifactId)
        assertEquals("3.12.0", rec.currentVersion)
        assertEquals("3.12.0", rec.providedVersion)
        assertEquals(RedundancyReason.DIRECT_DEPENDENCY_MATCH, rec.reason)
    }

    fun testScenario12_RulePrecedenceOverlap() {
        val context = loadScenario("12-rule-precedence-overlap")

        val parentRecs = findRecommendations(context, targetArtifactId = "rule-precedence-overlap")
        assertTrue("Parent POM should have no redundant managed dependencies", parentRecs.isEmpty())

        val childRecs = findRecommendations(context, targetArtifactId = "child")
        assertEquals(1, childRecs.size)
        val rec = childRecs.first()
        assertEquals("org.slf4j", rec.groupId)
        assertEquals("slf4j-api", rec.artifactId)
        assertEquals("2.0.9", rec.currentVersion)
        assertEquals("2.0.9", rec.providedVersion)
        assertEquals(RedundancyReason.PARENT_MANAGED, rec.reason)
    }

    fun testScenario13_ParentUnusedMultiModule() {
        val context = loadScenario("13-parent-unused-multi-module")

        val parentRecs = findRecommendations(context, targetArtifactId = "parent-unused-multi-module")
        assertEquals(1, parentRecs.size)
        val rec = parentRecs.first()
        assertEquals("org.apache.commons", rec.groupId)
        assertEquals("commons-collections4", rec.artifactId)
        assertEquals("4.4", rec.currentVersion)
        assertNull(rec.providedVersion)
        assertEquals(RedundancyReason.UNUSED, rec.reason)

        val moduleARecs = findRecommendations(context, targetArtifactId = "module-a")
        assertTrue("Module A has no redundant managed dependencies", moduleARecs.isEmpty())

        val moduleBRecs = findRecommendations(context, targetArtifactId = "module-b")
        assertTrue("Module B has no redundant managed dependencies", moduleBRecs.isEmpty())
    }

    fun testScenario14_VersionPinningOverride_NonRedundant() {
        val context = loadScenario("14-version-pinning-override")

        val parentRecs = findRecommendations(context, targetArtifactId = "version-pinning-override")
        assertTrue("Parent POM has no redundant managed dependencies", parentRecs.isEmpty())

        val childRecs = findRecommendations(context, targetArtifactId = "child")
        assertTrue("Pinned version is higher than parent; must not be marked redundant", childRecs.isEmpty())
    }

    fun testScenario15_TransitiveExclusion_NonRedundant() {
        val context = loadScenario("15-transitive-exclusion-non-redundant")

        val recs = findRecommendations(context)
        assertTrue("Managed entry is required due to exclusion; must not be marked redundant", recs.isEmpty())
    }
}
