package de.schwarzland.mavenup.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import de.schwarzland.mavenup.model.ConsumerDependencyInfo
import de.schwarzland.mavenup.model.RedundancyReason
import de.schwarzland.mavenup.model.RedundantManagedDependencyRecommendation
import de.schwarzland.mavenup.ui.MyMessageBundle
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

private val LOG = Logger.getInstance(RedundantManagedDependencyService::class.java)

/**
 * Analysiert, ob deklarierte verwaltete Abhängigkeiten (`<dependencyManagement>`) im aktuellen
 * Projektzustand (Ist-Zustand ohne Version-Upgrades) redundant sind und sicher aus der `pom.xml`
 * entfernt werden können.
 *
 * @property project Das IntelliJ-Projekt.
 * @property treeResolver Der [TemporaryDependencyTreeResolver] zum Laden von Parent-POMs und transitiven Abhängigkeiten.
 */
class RedundantManagedDependencyService(
    private val project: Project,
    private val treeResolver: TemporaryDependencyTreeResolver = TemporaryDependencyTreeResolver(project)
) {

    /**
     * Beschreibt eine deklarierte verwaltete Abhängigkeit aus dem Projekt.
     *
     * @property groupId Die Group-ID.
     * @property artifactId Die Artefakt-ID.
     * @property currentVersion Die deklarierte Version.
     * @property mavenProject Das zugehörige Maven-Projekt.
     */
    internal data class ManagedDependencyDeclaration(
        val groupId: String,
        val artifactId: String,
        val currentVersion: String,
        val mavenProject: MavenProject
    )

    /**
     * Ermittelt alle redundanten verwalteten Abhängigkeiten im aktuellen Projektzustand.
     *
     * @param managedCoordinate Optional: begrenzt die Ergebnisse auf diesen verwalteten Eintrag (`groupId:artifactId`).
     * @param indicator Optionaler Fortschrittsindikator für Statusdetails und Abbruch.
     * @return Liste der gefundenen redundanten verwalteten Abhängigkeiten.
     */
    fun findRedundantManagedDependencies(
        managedCoordinate: String? = null,
        indicator: ProgressIndicator? = null
    ): List<RedundantManagedDependencyRecommendation> {
        indicator?.checkCanceled()
        val mavenProjects = MavenProjectsManager.getInstance(project).projects.toList()
        LOG.debug(
            "Redundant managed dependencies analysis started: projects=${mavenProjects.size}, " +
                "managed=${managedCoordinate ?: "all"}"
        )

        val results = mutableListOf<RedundantManagedDependencyRecommendation>()
        for ((index, mavenProject) in mavenProjects.withIndex()) {
            indicator?.checkCanceled()
            val projectId = sourceProjectId(mavenProject)
            indicator?.text2 = "${index + 1}/${mavenProjects.size}: $projectId"
            val projectRecommendations = findRedundantForProject(
                mavenProject = mavenProject,
                allProjects = mavenProjects,
                managedCoordinate = managedCoordinate,
                indicator = indicator
            )
            results.addAll(projectRecommendations)
        }

        LOG.debug("Redundant managed dependencies analysis completed: results=${results.size}")
        return results
    }

    /**
     * Ermittelt Empfehlungen für ein konkretes Maven-Projekt.
     *
     * @param mavenProject Das zu analysierende Maven-Projekt.
     * @param allProjects Alle Maven-Projekte des Gesamtsystems zur Prüfung projektweiter Verwendungen.
     * @param managedCoordinate Optionaler Filter auf einen bestimmten verwalteten Eintrag.
     * @param indicator Optionaler Fortschrittsindikator.
     * @return Liste redundanter Einträge in diesem Modul.
     */
    internal fun findRedundantForProject(
        mavenProject: MavenProject,
        allProjects: List<MavenProject>,
        managedCoordinate: String?,
        indicator: ProgressIndicator?
    ): List<RedundantManagedDependencyRecommendation> {
        indicator?.checkCanceled()
        val managedDeclarations = collectManagedDependencies(mavenProject, indicator).filter {
            matchesCoordinateScope(it.groupId, it.artifactId, managedCoordinate)
        }
        if (managedDeclarations.isEmpty()) {
            return emptyList()
        }

        val directDependenciesWithExplicitVersion = collectDirectDependenciesWithExplicitVersions(mavenProject, indicator)
        val recommendations = mutableListOf<RedundantManagedDependencyRecommendation>()
        val projectId = sourceProjectId(mavenProject)

        for (managed in managedDeclarations) {
            indicator?.checkCanceled()
            indicator?.text2 = "$projectId: ${managed.groupId}:${managed.artifactId}"

            val recommendation = evaluateRedundancy(
                managed = managed,
                mavenProject = mavenProject,
                allProjects = allProjects,
                directDependenciesWithExplicitVersion = directDependenciesWithExplicitVersion,
                indicator = indicator
            )

            if (recommendation != null) {
                recommendations.add(recommendation)
            }
        }

        return recommendations
    }

    /**
     * Bewertet, ob und aus welchem Grund eine verwaltete Abhängigkeit redundant ist.
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param mavenProject Das zugehörige Maven-Projekt.
     * @param allProjects Alle Maven-Projekte im Workspace.
     * @param directDependenciesWithExplicitVersion Map von `groupId:artifactId` zu expliziter Version direkter Abhängigkeiten im Modul.
     * @param indicator Optionaler Fortschrittsindikator.
     * @return Eine [RedundantManagedDependencyRecommendation] oder `null`, falls der Eintrag wirksam und notwendig ist.
     */
    internal fun evaluateRedundancy(
        managed: ManagedDependencyDeclaration,
        mavenProject: MavenProject,
        allProjects: List<MavenProject>,
        directDependenciesWithExplicitVersion: Map<String, String>,
        indicator: ProgressIndicator?
    ): RedundantManagedDependencyRecommendation? {
        indicator?.checkCanceled()
        val key = "${managed.groupId}:${managed.artifactId}"
        val managedComp = ComparableVersion(managed.currentVersion)
        val consumerPaths = findProjectConsumersForManaged(mavenProject, managed.groupId, managed.artifactId, indicator)

        // 1. Kriterium: Parent-Verwaltung liefert dieselbe oder eine höhere Version
        val parentRec = evaluateParentManaged(managed, mavenProject, key, managedComp, consumerPaths)
        if (parentRec != null) return parentRec

        // 2. Kriterium: Direkte Abhängigkeit legt mindestens die verwaltete Version fest
        val directRec = evaluateDirectMatch(managed, key, managedComp, directDependenciesWithExplicitVersion, consumerPaths)
        if (directRec != null) return directRec

        // 3. Kriterium: Ungenutzter / verwaister Eintrag im Projekt
        val isUsedInProject = isUsedInAnyProject(allProjects, managed.groupId, managed.artifactId, indicator)
        if (!isUsedInProject) {
            return RedundantManagedDependencyRecommendation(
                groupId = managed.groupId,
                artifactId = managed.artifactId,
                currentVersion = managed.currentVersion,
                reason = RedundancyReason.UNUSED,
                reasonDetail = MyMessageBundle.message("redundant.managed.dependency.reason.unused.detail"),
                providedVersion = null,
                consumers = emptyList(),
                sourceProjectId = sourceProjectId(managed.mavenProject),
                sourcePomPath = managed.mavenProject.file.path
            )
        }

        // 4. Kriterium: Identische Bereitstellung durch aktuelle transitive Abhängigkeiten
        if (consumerPaths.isNotEmpty()) {
            return evaluateTransitiveMatch(
                managed = managed,
                managedComp = managedComp,
                consumerPaths = consumerPaths,
                indicator = indicator
            )
        }

        return null
    }

    /**
     * Prüft, ob die effektive Parent-Verwaltung dieselbe oder eine höhere Version als der lokale Eintrag liefert.
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param mavenProject Das Maven-Projekt mit Parent-Deklaration.
     * @param key Koordinate `groupId:artifactId` der verwalteten Abhängigkeit.
     * @param managedComp Die vergleichbare Version des lokalen Eintrags.
     * @param consumerPaths Die bekannten Pfade, die das Artefakt im Projekt verwenden.
     * @return Eine Empfehlung bei gleicher oder höherer Parent-Version oder `null`.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun evaluateParentManaged(
        managed: ManagedDependencyDeclaration,
        mavenProject: MavenProject,
        key: String,
        managedComp: ComparableVersion,
        consumerPaths: List<List<MavenArtifactNode>>
    ): RedundantManagedDependencyRecommendation? {
        val parentInfo = extractParentInfo(mavenProject) ?: return null
        return try {
            val parentMgmt = treeResolver.resolveEffectiveDependencyManagement(
                parentInfo.groupId,
                parentInfo.artifactId,
                parentInfo.version
            )
            val parentVersion = parentMgmt[key]
            if (parentVersion != null && ComparableVersion(parentVersion).compareTo(managedComp) >= 0) {
                val consumerInfos = formatConsumerInfos(
                    consumerPaths = consumerPaths,
                    resolvedVersion = { parentVersion }
                )
                RedundantManagedDependencyRecommendation(
                    groupId = managed.groupId,
                    artifactId = managed.artifactId,
                    currentVersion = managed.currentVersion,
                    reason = RedundancyReason.PARENT_MANAGED,
                    reasonDetail = MyMessageBundle.message(
                        "redundant.managed.dependency.reason.parentManaged.detail",
                        "${parentInfo.groupId}:${parentInfo.artifactId}:${parentInfo.version}",
                        key,
                        parentVersion,
                        managed.currentVersion,
                        if (ComparableVersion(parentVersion).compareTo(managedComp) == 0) {
                            MyMessageBundle.message("redundant.managed.dependency.versionRelation.same")
                        } else {
                            MyMessageBundle.message("redundant.managed.dependency.versionRelation.higher")
                        }
                    ),
                    providedVersion = parentVersion,
                    consumers = consumerInfos,
                    sourceProjectId = sourceProjectId(managed.mavenProject),
                    sourcePomPath = managed.mavenProject.file.path
                )
            } else {
                null
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Could not check parent dependencyManagement for $parentInfo (${e.javaClass.simpleName})", e)
            null
        }
    }

    /**
     * Prüft, ob eine direkte Deklaration mindestens die verwaltete Version festlegt und keine weiteren bekannten Pfade auf Management angewiesen sind.
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param key Koordinate `groupId:artifactId` der verwalteten Abhängigkeit.
     * @param managedComp Die vergleichbare Version des lokalen Eintrags.
     * @param directDependenciesWithExplicitVersion Direkte Abhängigkeiten mit expliziter Version.
     * @param consumerPaths Die bekannten Pfade, die das Artefakt im Projekt verwenden.
     * @return Eine Empfehlung, wenn die direkte Deklaration dieselbe oder eine höhere Version festlegt, sonst `null`.
     */
    internal fun evaluateDirectMatch(
        managed: ManagedDependencyDeclaration,
        key: String,
        managedComp: ComparableVersion,
        directDependenciesWithExplicitVersion: Map<String, String>,
        consumerPaths: List<List<MavenArtifactNode>>
    ): RedundantManagedDependencyRecommendation? {
        val directVersion = directDependenciesWithExplicitVersion[key] ?: return null
        val otherManagedConsumers = consumerPaths.any { path ->
            path.size > 1 || path.firstOrNull()?.artifact?.let {
                it.groupId != managed.groupId || it.artifactId != managed.artifactId
            } == true
        }
        if (ComparableVersion(directVersion).compareTo(managedComp) >= 0 && !otherManagedConsumers) {
            val consumerInfos = formatConsumerInfos(
                consumerPaths = consumerPaths,
                resolvedVersion = { directVersion }
            )
            return RedundantManagedDependencyRecommendation(
                groupId = managed.groupId,
                artifactId = managed.artifactId,
                currentVersion = managed.currentVersion,
                reason = RedundancyReason.DIRECT_DEPENDENCY_MATCH,
                reasonDetail = MyMessageBundle.message(
                    "redundant.managed.dependency.reason.directMatch.detail",
                    key,
                    directVersion,
                    managed.currentVersion,
                    if (ComparableVersion(directVersion).compareTo(managedComp) == 0) {
                        MyMessageBundle.message("redundant.managed.dependency.versionRelation.same")
                    } else {
                        MyMessageBundle.message("redundant.managed.dependency.versionRelation.higher")
                    }
                ),
                providedVersion = directVersion,
                consumers = consumerInfos,
                sourceProjectId = sourceProjectId(managed.mavenProject),
                sourcePomPath = managed.mavenProject.file.path
            )
        }
        return null
    }

    /**
     * Prüft, ob alle Konsumentenpfade von ihren direkten Ursprungsabhängigkeiten mindestens die verwaltete Version erhalten.
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param managedComp Die [ComparableVersion] der aktuellen verwalteten Version.
     * @param consumerPaths Alle ermittelten Konsumentenpfade im Modul.
     * @param indicator Optionaler Fortschrittsindikator.
     * @return [RedundantManagedDependencyRecommendation] oder `null`.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun evaluateTransitiveMatch(
        managed: ManagedDependencyDeclaration,
        managedComp: ComparableVersion,
        consumerPaths: List<List<MavenArtifactNode>>,
        indicator: ProgressIndicator?
    ): RedundantManagedDependencyRecommendation? {
        val key = "${managed.groupId}:${managed.artifactId}"
        var allMatch = true
        val providedVersionsByRoot = mutableMapOf<String, String>()

        val rootArtifacts = consumerPaths.mapNotNull { it.firstOrNull()?.artifact }
            .distinctBy { "${it.groupId}:${it.artifactId}:${it.version}" }
        if (rootArtifacts.isEmpty()) return null

        for (root in rootArtifacts) {
            indicator?.checkCanceled()
            val transitives = try {
                treeResolver.resolveTransitiveDependencies(root.groupId, root.artifactId, root.version)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Could not resolve transitive dependencies for ${root.groupId}:${root.artifactId}:${root.version}", e)
                emptyMap()
            }

            val transVersion = transitives[key]
            if (transVersion == null || ComparableVersion(transVersion).compareTo(managedComp) < 0) {
                allMatch = false
                break
            }
            providedVersionsByRoot["${root.groupId}:${root.artifactId}:${root.version}"] = transVersion
        }

        if (allMatch && providedVersionsByRoot.isNotEmpty()) {
            val consumerInfos = formatConsumerInfos(consumerPaths) { root ->
                providedVersionsByRoot["${root.groupId}:${root.artifactId}:${root.version}"].orEmpty()
            }
            val providedVersions = providedVersionsByRoot.values.distinct()
            val providedVersion = providedVersions.joinToString(", ")
            return RedundantManagedDependencyRecommendation(
                groupId = managed.groupId,
                artifactId = managed.artifactId,
                currentVersion = managed.currentVersion,
                reason = RedundancyReason.TRANSITIVE_MATCH,
                reasonDetail = MyMessageBundle.message(
                    "redundant.managed.dependency.reason.transitiveMatch.detail",
                    key,
                    managed.currentVersion,
                    providedVersion
                ),
                providedVersion = providedVersion,
                consumers = consumerInfos,
                sourceProjectId = sourceProjectId(managed.mavenProject),
                sourcePomPath = managed.mavenProject.file.path
            )
        }

        return null
    }

    /**
     * Beschreibt ein deklariertes oder aufgelöstes Parent-POM.
     */
    internal data class ParentInfo(
        val groupId: String,
        val artifactId: String,
        val version: String
    )

    /**
     * Ermittelt die Parent-POM-Koordinaten aus dem Maven-Modell oder der XML-Deklaration.
     */
    internal fun extractParentInfo(mavenProject: MavenProject): ParentInfo? {
        val parentId = try { mavenProject.parentId } catch (_: NullPointerException) { null }
        val parentGroupId = parentId?.groupId
        val parentArtifactId = parentId?.artifactId
        val parentVersion = parentId?.version
        if (!parentGroupId.isNullOrEmpty() && !parentArtifactId.isNullOrEmpty() && !parentVersion.isNullOrEmpty()) {
            return ParentInfo(parentGroupId, parentArtifactId, parentVersion)
        }

        var result: ParentInfo? = null
        val effectiveProperties = try {
            mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }
        } catch (_: NullPointerException) {
            emptyMap()
        }
        ApplicationManager.getApplication().runReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile ?: return@runReadAction
            val rootTag = psiFile.document?.rootTag ?: return@runReadAction
            val parentTag = rootTag.findFirstSubTag("parent") ?: return@runReadAction
            val g = parentTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
            val a = parentTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
            val v = parentTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
            if (g.isNotEmpty() && a.isNotEmpty()) {
                val resolvedV = if (v.isNotEmpty()) resolvePropertyPlaceholder(v, effectiveProperties) else ""
                if (resolvedV.isNotEmpty()) {
                    result = ParentInfo(g, a, resolvedV)
                }
            }
        }
        return result
    }

    /**
     * Prüft, ob eine Abhängigkeit in irgendeinem Modul des Projekts direkt oder transitiv vorkommt.
     *
     * @param projects Alle Maven-Projekte im Workspace.
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param indicator Optionaler Fortschrittsindikator.
     * @return `true`, wenn mindestens ein Modul die Abhängigkeit direkt oder transitiv referenziert.
     */
    internal fun isUsedInAnyProject(
        projects: List<MavenProject>,
        groupId: String,
        artifactId: String,
        indicator: ProgressIndicator?
    ): Boolean {
        for (p in projects) {
            indicator?.checkCanceled()
            val deps = try { p.dependencies } catch (_: NullPointerException) { null }
            if (deps?.any { it.groupId == groupId && it.artifactId == artifactId } == true) {
                return true
            }
            val tree = try { p.dependencyTree } catch (_: NullPointerException) { null }
            for (rootNode in tree.orEmpty()) {
                if (containsArtifact(rootNode, groupId, artifactId, mutableSetOf())) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Durchsucht einen Teilbaum rekursiv nach einer Maven-Koordinate.
     */
    private fun containsArtifact(
        node: MavenArtifactNode,
        targetGroupId: String,
        targetArtifactId: String,
        visited: MutableSet<MavenArtifactNode>
    ): Boolean {
        val art = node.artifact
        if (art.groupId == targetGroupId && art.artifactId == targetArtifactId) {
            return true
        }
        if (!visited.add(node)) return false
        for (child in node.dependencies) {
            if (containsArtifact(child, targetGroupId, targetArtifactId, visited)) {
                return true
            }
        }
        return false
    }

    /**
     * Formatiert Konsumentenpfade in [ConsumerDependencyInfo]-Objekte.
     *
     * @param consumerPaths Die bekannten Maven-Abhängigkeitspfade.
     * @param resolvedVersion Die ohne den lokalen Management-Eintrag bereitgestellte Version je Pfadursprung.
     * @return Konsumenteninformationen mit Pfad und bereitgestellter Version.
     */
    private fun formatConsumerInfos(
        consumerPaths: List<List<MavenArtifactNode>>,
        resolvedVersion: (org.jetbrains.idea.maven.model.MavenArtifact) -> String
    ): List<ConsumerDependencyInfo> {
        return consumerPaths.map { path ->
            val root = path.first().artifact
            val pathDesc = path.joinToString(" -> ") {
                "${it.artifact.groupId}:${it.artifact.artifactId}:${it.artifact.version}"
            }
            ConsumerDependencyInfo(
                groupId = root.groupId,
                artifactId = root.artifactId,
                resolvedVersion = resolvedVersion(root),
                pathDescription = pathDesc
            )
        }
    }

    /**
     * Findet alle Pfade im Abhängigkeitsbaum des Projekts, die zur gesuchten Koordinate führen.
     *
     * @param mavenProject Das Maven-Projekt.
     * @param managedGroupId Die gesuchte Group-ID.
     * @param managedArtifactId Die gesuchte Artefakt-ID.
     * @param indicator Optionaler Fortschrittsindikator.
     * @return Liste aller Pfade vom Wurzelknoten bis zur Zielabhängigkeit.
     */
    internal fun findProjectConsumersForManaged(
        mavenProject: MavenProject,
        managedGroupId: String,
        managedArtifactId: String,
        indicator: ProgressIndicator?
    ): List<List<MavenArtifactNode>> {
        val results = mutableListOf<List<MavenArtifactNode>>()
        val tree = try { mavenProject.dependencyTree } catch (_: NullPointerException) { null }
        for (rootNode in tree.orEmpty()) {
            indicator?.checkCanceled()
            findPathsToTarget(
                currentNode = rootNode,
                targetGroupId = managedGroupId,
                targetArtifactId = managedArtifactId,
                currentPath = emptyList(),
                visited = mutableSetOf(),
                result = results,
                indicator = indicator
            )
        }
        return results
    }

    /**
     * Rekursive Tiefensuche für Abhängigkeitspfade.
     */
    private fun findPathsToTarget(
        currentNode: MavenArtifactNode,
        targetGroupId: String,
        targetArtifactId: String,
        currentPath: List<MavenArtifactNode>,
        visited: MutableSet<MavenArtifactNode>,
        result: MutableList<List<MavenArtifactNode>>,
        indicator: ProgressIndicator?
    ) {
        indicator?.checkCanceled()
        val newPath = currentPath + currentNode
        val art = currentNode.artifact
        if (art.groupId == targetGroupId && art.artifactId == targetArtifactId) {
            result.add(newPath)
            return
        }

        if (!visited.add(currentNode)) return

        val childDeps = try { currentNode.dependencies } catch (_: NullPointerException) { null }
        for (child in childDeps.orEmpty()) {
            findPathsToTarget(child, targetGroupId, targetArtifactId, newPath, visited, result, indicator)
        }
    }

    /**
     * Liest die deklarierten `<dependencyManagement>`-Abhängigkeiten aus der `pom.xml` des Projekts.
     * Schließt `<type>pom</type>` mit `<scope>import</scope>` (BOM-Imports) aus.
     */
    internal fun collectManagedDependencies(
        mavenProject: MavenProject,
        indicator: ProgressIndicator? = null
    ): List<ManagedDependencyDeclaration> {
        val declarations = mutableListOf<ManagedDependencyDeclaration>()
        val effectiveProperties = try {
            mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }
        } catch (_: NullPointerException) {
            emptyMap()
        }
        val resolvedDependencies = try {
            mavenProject.dependencyTree.associateBy { "${it.artifact.groupId}:${it.artifact.artifactId}" }
        } catch (_: NullPointerException) {
            emptyMap()
        }

        ApplicationManager.getApplication().runReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile ?: return@runReadAction
            val rootTag = psiFile.document?.rootTag ?: return@runReadAction
            val dmTag = rootTag.findFirstSubTag("dependencyManagement")
            val dmDepsTag = dmTag?.findFirstSubTag("dependencies")
            dmDepsTag?.findSubTags("dependency")?.forEach { depTag ->
                indicator?.checkCanceled()
                val g = depTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
                val a = depTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
                val v = depTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
                val type = depTag.findFirstSubTag("type")?.value?.text?.trim()
                val scope = depTag.findFirstSubTag("scope")?.value?.text?.trim()

                // BOM-Imports überspringen
                if (type.equals("pom", ignoreCase = true) && scope.equals("import", ignoreCase = true)) {
                    return@forEach
                }

                if (g.isNotEmpty() && a.isNotEmpty()) {
                    val rawOrResolved = resolveDependencyVersion(g, a, v, effectiveProperties, mavenProject, resolvedDependencies)
                    if (rawOrResolved.isNotEmpty()) {
                        declarations.add(ManagedDependencyDeclaration(g, a, rawOrResolved, mavenProject))
                    }
                }
            }
        }
        return declarations
    }

    /**
     * Liest alle direkten `<dependencies>` aus der `pom.xml` ein, die ein explizites `<version>`-Tag besitzen.
     */
    internal fun collectDirectDependenciesWithExplicitVersions(
        mavenProject: MavenProject,
        indicator: ProgressIndicator? = null
    ): Map<String, String> {
        val directVersions = mutableMapOf<String, String>()
        val effectiveProperties = try {
            mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }
        } catch (_: NullPointerException) {
            emptyMap()
        }

        ApplicationManager.getApplication().runReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile ?: return@runReadAction
            val rootTag = psiFile.document?.rootTag ?: return@runReadAction
            val depsTag = rootTag.findFirstSubTag("dependencies")
            depsTag?.findSubTags("dependency")?.forEach { depTag ->
                indicator?.checkCanceled()
                val g = depTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
                val a = depTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
                val v = depTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
                if (g.isNotEmpty() && a.isNotEmpty() && v.isNotEmpty()) {
                    val resolved = resolvePropertyPlaceholder(v, effectiveProperties)
                    directVersions["$g:$a"] = resolved.ifEmpty { v }
                }
            }
        }
        return directVersions
    }

    /**
     * Löst eine Versionsangabe auf.
     */
    private fun resolveDependencyVersion(
        groupId: String,
        artifactId: String,
        rawVersion: String,
        effectiveProperties: Map<String, String>,
        mavenProject: MavenProject,
        resolvedDependencies: Map<String, MavenArtifactNode>
    ): String {
        if (rawVersion.isNotEmpty()) {
            val resolved = resolvePropertyPlaceholder(rawVersion, effectiveProperties)
            return resolved.ifEmpty { rawVersion }
        }
        val fromTree = resolvedDependencies["$groupId:$artifactId"]?.artifact?.version
        if (fromTree != null) return fromTree
        val fromFind = try {
            mavenProject.findDependencies(groupId, artifactId).firstOrNull()?.version
        } catch (_: NullPointerException) {
            null
        }
        if (fromFind != null) return fromFind
        val fromDeps = try {
            mavenProject.dependencies
                .firstOrNull { it.groupId == groupId && it.artifactId == artifactId }
                ?.version
                .orEmpty()
        } catch (_: NullPointerException) {
            ""
        }
        return fromDeps
    }

    /**
     * Löst Platzhalter der Form `${property.name}` auf.
     */
    private fun resolvePropertyPlaceholder(rawVersion: String, properties: Map<String, String>): String {
        if (!rawVersion.startsWith("\${") || !rawVersion.endsWith("}")) {
            return rawVersion
        }
        val propName = rawVersion.removeSurrounding("\${", "}").trim()
        return properties[propName] ?: rawVersion
    }

    /**
     * Prüft den Koordinatenfilter.
     */
    internal fun matchesCoordinateScope(groupId: String, artifactId: String, coordinate: String?): Boolean =
        coordinate == null || "$groupId:$artifactId" == coordinate

    /**
     * Erzeugt eine lesbare Projekt-ID.
     */
    internal fun sourceProjectId(mavenProject: MavenProject): String {
        val name = mavenProject.name.orEmpty()
        val fileName = mavenProject.file.name
        return try {
            val id = mavenProject.mavenId.toString()
            if (id.isNotBlank()) id else if (name.isNotBlank()) name else fileName
        } catch (_: NullPointerException) {
            LOG.debug("Maven coordinates are not available for ${mavenProject.file.path}")
            if (name.isNotBlank()) name else fileName
        }
    }
}
