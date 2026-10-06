package de.schwarzland.mavenup.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import de.schwarzland.mavenup.model.ConsumerDependencyInfo
import de.schwarzland.mavenup.model.ManagedDependencyRemovalRecommendation
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

private val LOG = Logger.getInstance(ManagedDependencyRecommendationService::class.java)

/**
 * Analysiert, ob deklarierte verwaltete Abhängigkeiten (`<dependencyManagement>`) durch
 * Versionsaktualisierungen von übergeordneten POMs (`<parent>`) oder direkten Abhängigkeiten
 * redundant werden und sicher aus der `pom.xml` entfernt werden können.
 *
 * @property project Das IntelliJ-Projekt.
 * @property treeResolver Der [TemporaryDependencyTreeResolver] zum Laden und Analysieren von Kandidaten-POMs.
 * @property candidateVersionsProvider Optionaler Provider für verfügbare Kandidatenversionen (z. B. für Tests).
 */
class ManagedDependencyRecommendationService(
    private val project: Project,
    private val treeResolver: TemporaryDependencyTreeResolver = TemporaryDependencyTreeResolver(project),
    private val candidateVersionsProvider: ((groupId: String, artifactId: String, currentVersion: String) -> List<String>)? = null
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
     * Beschreibt eine updatefähige auslösende Komponente (Parent oder direkte Dependency).
     *
     * @property groupId Die Group-ID.
     * @property artifactId Die Artefakt-ID.
     * @property currentVersion Die aktuelle Version im Projekt.
     * @property type Der Typ (`"parent"` oder `"dependency"`).
     * @property candidateVersions Liste verfügbarer neuerer Versionen (`> currentVersion`).
     * @property mavenProject Das zugehörige Maven-Projekt.
     */
    internal data class TriggerCandidate(
        val groupId: String,
        val artifactId: String,
        val currentVersion: String,
        val type: String,
        val candidateVersions: List<String>,
        val mavenProject: MavenProject
    )

    /**
     * Ermittelt alle Empfehlungen zur Bereinigung redundanter verwalteter Abhängigkeiten.
     * Protokolliert Prüfumfang und Ergebnisanzahl auf DEBUG-Ebene für Toolbar und Kontextmenü.
     *
     * @param availableVersionsMap Optionale Map mit bereits ermittelten Versionen (`groupId:artifactId` -> Versionsliste).
     * @param managedCoordinate Optional: begrenzt die Ergebnisse auf diesen verwalteten Eintrag.
     * @param triggerCoordinate Optional: begrenzt die Ergebnisse auf Updates dieses Triggers.
     * @return Liste der gefundenen Empfehlungen, bei denen alle Konsumenten kompatibel versorgt werden.
     */
    fun findRecommendations(
        availableVersionsMap: Map<String, List<String>> = emptyMap(),
        managedCoordinate: String? = null,
        triggerCoordinate: String? = null
    ): List<ManagedDependencyRemovalRecommendation> =
        findRecommendations(availableVersionsMap, managedCoordinate, triggerCoordinate, null)

    /**
     * Ermittelt Empfehlungen mit optionaler Fortschrittsanzeige und Abbruchunterstützung.
     *
     * @param availableVersionsMap Bereits ermittelte Versionen (`groupId:artifactId` -> Versionsliste).
     * @param managedCoordinate Optionaler Filter für den verwalteten Eintrag.
     * @param triggerCoordinate Optionaler Filter für den Upgrade-Auslöser.
     * @param indicator Optionaler Fortschrittsindikator für Statusdetails und Abbruch.
     * @return Liste der gefundenen Empfehlungen, bei denen alle Konsumenten kompatibel versorgt werden.
     */
    internal fun findRecommendations(
        availableVersionsMap: Map<String, List<String>>,
        managedCoordinate: String?,
        triggerCoordinate: String?,
        indicator: ProgressIndicator?
    ): List<ManagedDependencyRemovalRecommendation> {
        indicator?.checkCanceled()
        val mavenProjects = MavenProjectsManager.getInstance(project).projects.toList()
        LOG.debug(
            "Cleanup analysis started: projects=${mavenProjects.size}, " +
                "managed=${managedCoordinate ?: "all"}, trigger=${triggerCoordinate ?: "all"}"
        )
        val recommendations = mavenProjects.flatMapIndexed { index, mavenProject ->
            indicator?.checkCanceled()
            indicator?.text2 = "${index + 1}/${mavenProjects.size}: ${mavenProject.mavenId}"
            findRecommendationsForProject(
                mavenProject,
                availableVersionsMap,
                managedCoordinate,
                triggerCoordinate,
                indicator
            )
        }
        LOG.debug("Cleanup analysis completed: recommendations=${recommendations.size}")
        return recommendations
    }

    /**
     * Ermittelt Empfehlungen für ein konkretes Maven-Projekt.
     *
     * @param mavenProject Das zu analysierende Maven-Projekt.
     * @param availableVersionsMap Bereits bekannte verfügbare Versionen.
     * @param managedCoordinate Optional: begrenzt die Analyse auf diesen verwalteten Eintrag.
     * @param triggerCoordinate Optional: begrenzt die Analyse auf Updates dieses Triggers.
     * @param indicator Optionaler Fortschrittsindikator für Statusdetails und Abbruch.
     * @return Liste der Empfehlungen für dieses Projekt.
     */
    private fun findRecommendationsForProject(
        mavenProject: MavenProject,
        availableVersionsMap: Map<String, List<String>>,
        managedCoordinate: String?,
        triggerCoordinate: String?,
        indicator: ProgressIndicator?
    ): List<ManagedDependencyRemovalRecommendation> {
        indicator?.checkCanceled()
        indicator?.text2 = "${mavenProject.mavenId}: dependencyManagement"
        val managedDeclarations = collectManagedDependencies(mavenProject, indicator).filter {
            matchesCoordinateScope(it.groupId, it.artifactId, managedCoordinate)
        }
        LOG.debug("Cleanup project ${mavenProject.mavenId}: managed entries=${managedDeclarations.size}")
        if (managedDeclarations.isEmpty()) {
            LOG.debug("Skipping cleanup project ${mavenProject.mavenId}: no managed entries in scope")
            return emptyList()
        }

        indicator?.checkCanceled()
        indicator?.text2 = "${mavenProject.mavenId}: upgrade candidates"
        val triggers = collectTriggerCandidates(mavenProject, availableVersionsMap, indicator).filter {
            matchesCoordinateScope(it.groupId, it.artifactId, triggerCoordinate)
        }
        LOG.debug("Cleanup project ${mavenProject.mavenId}: upgrade triggers=${triggers.size}")
        if (triggers.isEmpty()) {
            LOG.debug("Skipping cleanup project ${mavenProject.mavenId}: no newer trigger versions in scope")
            return emptyList()
        }

        val results = mutableListOf<ManagedDependencyRemovalRecommendation>()
        for (managed in managedDeclarations) {
            indicator?.checkCanceled()
            indicator?.text2 = "${mavenProject.mavenId}: ${managed.groupId}:${managed.artifactId}"
            val managedComparable = ComparableVersion(managed.currentVersion)
            val consumerPaths = findProjectConsumersForManaged(
                mavenProject,
                managed.groupId,
                managed.artifactId,
                indicator
            )
            LOG.debug(
                "Cleanup managed entry ${managed.groupId}:${managed.artifactId}:${managed.currentVersion}: " +
                    "consumer paths=${consumerPaths.size}"
            )

            for (trigger in triggers) {
                indicator?.checkCanceled()
                indicator?.text2 =
                    "${mavenProject.mavenId}: ${managed.groupId}:${managed.artifactId} -> " +
                    "${trigger.groupId}:${trigger.artifactId}"
                if (trigger.groupId == managed.groupId && trigger.artifactId == managed.artifactId) {
                    LOG.debug("Skipping cleanup trigger ${trigger.groupId}:${trigger.artifactId}: same as managed entry")
                    continue
                }
                val rec = findFirstSatisfiedRecommendationForTrigger(
                    managed,
                    managedComparable,
                    trigger,
                    consumerPaths,
                    indicator
                )
                if (rec != null) {
                    results.add(rec)
                }
            }
        }
        return results
    }

    /**
     * Prüft, ob eine Maven-Koordinate dem optionalen Koordinatenfilter entspricht.
     *
     * @param groupId Die Group-ID der Koordinate.
     * @param artifactId Die Artefakt-ID der Koordinate.
     * @param coordinate Optionaler Filter im Format `groupId:artifactId`.
     * @return `true`, wenn kein Filter gesetzt ist oder die Koordinate exakt übereinstimmt.
     */
    internal fun matchesCoordinateScope(groupId: String, artifactId: String, coordinate: String?): Boolean =
        coordinate == null || "$groupId:$artifactId" == coordinate

    /**
     * Findet die erste passende Zielversion eines Triggers, die alle Konsumenten kompatibel versorgt.
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param managedComparable Die [ComparableVersion] der aktuellen verwalteten Version.
     * @param trigger Der auslösende Update-Kandidat.
     * @param consumerPaths Alle im aktuellen Projekt gefundenen Konsumenten-Pfade.
     * @param indicator Optionaler Fortschrittsindikator für Statusdetails und Abbruch.
     * @return Die gefundene Empfehlung oder `null`.
     */
    private fun findFirstSatisfiedRecommendationForTrigger(
        managed: ManagedDependencyDeclaration,
        managedComparable: ComparableVersion,
        trigger: TriggerCandidate,
        consumerPaths: List<List<MavenArtifactNode>>,
        indicator: ProgressIndicator?
    ): ManagedDependencyRemovalRecommendation? {
        val eligibleVersions = trigger.candidateVersions.filter {
            indicator?.checkCanceled()
            ComparableVersion(it) > ComparableVersion(trigger.currentVersion)
        }
        LOG.debug(
            "Cleanup candidate versions for ${trigger.groupId}:${trigger.artifactId}:${trigger.currentVersion}: " +
                summarizeForDebugLog(eligibleVersions)
        )
        for (targetVersion in eligibleVersions) {
            indicator?.checkCanceled()
            indicator?.text2 =
                "${managed.groupId}:${managed.artifactId} -> ${trigger.groupId}:${trigger.artifactId}:$targetVersion"
            val recommendation = evaluateTriggerRecommendation(
                managed = managed,
                managedComparable = managedComparable,
                trigger = trigger,
                targetVersion = targetVersion,
                consumerPaths = consumerPaths,
                indicator = indicator
            )
            if (recommendation != null && recommendation.isSatisfiedAcrossAllConsumers) {
                return recommendation
            }
        }
        return null
    }

    /**
     * Bewertet, ob ein Kandidaten-Upgrade für ein bestimmtes Managed Dependency eine valide Empfehlung ergibt.
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param managedComparable Die [ComparableVersion] der aktuellen verwalteten Version.
     * @param trigger Der auslösende Update-Kandidat.
     * @param targetVersion Die geprüfte Zielversion des Triggers.
     * @param consumerPaths Alle im aktuellen Projekt gefundenen Konsumenten-Pfade der verwalteten Abhängigkeit.
     * @return Eine [ManagedDependencyRemovalRecommendation] oder `null`.
     */
    internal fun evaluateTriggerRecommendation(
        managed: ManagedDependencyDeclaration,
        managedComparable: ComparableVersion,
        trigger: TriggerCandidate,
        targetVersion: String,
        consumerPaths: List<List<MavenArtifactNode>>
    ): ManagedDependencyRemovalRecommendation? =
        evaluateTriggerRecommendation(managed, managedComparable, trigger, targetVersion, consumerPaths, null)

    /**
     * Bewertet ein Kandidaten-Upgrade und berücksichtigt bei Bedarf den Abbruchindikator.
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param managedComparable Die [ComparableVersion] der aktuellen verwalteten Version.
     * @param trigger Der auslösende Update-Kandidat.
     * @param targetVersion Die geprüfte Zielversion des Triggers.
     * @param consumerPaths Alle Konsumenten-Pfade der verwalteten Abhängigkeit.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
     * @return Eine [ManagedDependencyRemovalRecommendation] oder `null`.
     */
    internal fun evaluateTriggerRecommendation(
        managed: ManagedDependencyDeclaration,
        managedComparable: ComparableVersion,
        trigger: TriggerCandidate,
        targetVersion: String,
        consumerPaths: List<List<MavenArtifactNode>>,
        indicator: ProgressIndicator?
    ): ManagedDependencyRemovalRecommendation? {
        indicator?.checkCanceled()
        LOG.debug(
            "Checking cleanup for ${managed.groupId}:${managed.artifactId}:${managed.currentVersion} " +
                "with ${trigger.type} ${trigger.groupId}:${trigger.artifactId}:${trigger.currentVersion} -> $targetVersion"
        )
        val recommendation = if (trigger.type == "parent") {
            evaluateParentTriggerRecommendation(managed, managedComparable, trigger, targetVersion, consumerPaths, indicator)
        } else if (trigger.type == "dependency") {
            evaluateDependencyTriggerRecommendation(managed, managedComparable, trigger, targetVersion, consumerPaths, indicator)
        } else {
            null
        }
        LOG.debug(
            "Cleanup candidate result for ${managed.groupId}:${managed.artifactId} " +
                "via ${trigger.groupId}:${trigger.artifactId}:$targetVersion: " +
                "provided=${recommendation?.transitiveVersionInTarget ?: "none compatible"}, " +
                "all consumers satisfied=${recommendation?.isSatisfiedAcrossAllConsumers ?: false}"
        )
        return recommendation
    }

    /**
     * Bewertet eine Bereinigungsempfehlung für ein übergeordnetes POM (`<parent>`).
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param managedComparable Die [ComparableVersion] der aktuellen verwalteten Version.
     * @param trigger Der auslösende Parent-Kandidat.
     * @param targetVersion Die geprüfte Zielversion.
     * @param consumerPaths Alle Konsumenten-Pfade im Projekt.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
     * @return Eine [ManagedDependencyRemovalRecommendation] oder `null`.
     */
    private fun evaluateParentTriggerRecommendation(
        managed: ManagedDependencyDeclaration,
        managedComparable: ComparableVersion,
        trigger: TriggerCandidate,
        targetVersion: String,
        consumerPaths: List<List<MavenArtifactNode>>,
        indicator: ProgressIndicator?
    ): ManagedDependencyRemovalRecommendation? {
        indicator?.checkCanceled()
        val parentDepMgmt = treeResolver.resolveEffectiveDependencyManagement(trigger.groupId, trigger.artifactId, targetVersion)
        indicator?.checkCanceled()
        val parentTransitives = treeResolver.resolveTransitiveDependencies(trigger.groupId, trigger.artifactId, targetVersion)
        indicator?.checkCanceled()
        val key = "${managed.groupId}:${managed.artifactId}"
        val providedVersion = parentDepMgmt[key] ?: parentTransitives[key]
        if (providedVersion == null) {
            LOG.debug("Cleanup parent candidate ${trigger.groupId}:${trigger.artifactId}:$targetVersion does not provide $key")
            return null
        }

        if (ComparableVersion(providedVersion) < managedComparable) {
            LOG.debug("Cleanup rejected for $key: provided $providedVersion is older than ${managed.currentVersion}")
            return null
        }

        val consumerInfos = if (consumerPaths.isEmpty()) {
            listOf(
                ConsumerDependencyInfo(
                    groupId = trigger.groupId,
                    artifactId = trigger.artifactId,
                    resolvedVersion = providedVersion,
                    pathDescription = "Parent POM ${trigger.artifactId}:$targetVersion manages ${managed.artifactId}:$providedVersion"
                )
            )
        } else {
            consumerPaths.map { path ->
                val root = path.first().artifact
                val pathDesc = path.joinToString(" -> ") { "${it.artifact.artifactId}:${it.artifact.version}" }
                ConsumerDependencyInfo(
                    groupId = root.groupId,
                    artifactId = root.artifactId,
                    resolvedVersion = providedVersion,
                    pathDescription = pathDesc
                )
            }
        }

        return ManagedDependencyRemovalRecommendation(
            managedGroupId = managed.groupId,
            managedArtifactId = managed.artifactId,
            managedCurrentVersion = managed.currentVersion,
            triggerGroupId = trigger.groupId,
            triggerArtifactId = trigger.artifactId,
            triggerType = trigger.type,
            triggerCurrentVersion = trigger.currentVersion,
            triggerTargetVersion = targetVersion,
            transitiveVersionInTarget = providedVersion,
            consumers = consumerInfos,
            isSatisfiedAcrossAllConsumers = true
        )
    }

    /**
     * Bewertet eine Bereinigungsempfehlung für eine direkte Abhängigkeit (`<dependency>`).
     *
     * @param managed Die deklarierte verwaltete Abhängigkeit.
     * @param managedComparable Die [ComparableVersion] der aktuellen verwalteten Version.
     * @param trigger Der auslösende Dependency-Kandidat.
     * @param targetVersion Die geprüfte Zielversion.
     * @param consumerPaths Alle Konsumenten-Pfade im Projekt.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
     * @return Eine [ManagedDependencyRemovalRecommendation] oder `null`.
     */
    private fun evaluateDependencyTriggerRecommendation(
        managed: ManagedDependencyDeclaration,
        managedComparable: ComparableVersion,
        trigger: TriggerCandidate,
        targetVersion: String,
        consumerPaths: List<List<MavenArtifactNode>>,
        indicator: ProgressIndicator?
    ): ManagedDependencyRemovalRecommendation? {
        indicator?.checkCanceled()
        val candidateTransitives = treeResolver.resolveTransitiveDependencies(trigger.groupId, trigger.artifactId, targetVersion)
        indicator?.checkCanceled()
        val key = "${managed.groupId}:${managed.artifactId}"
        val providedVersion = candidateTransitives[key]
        if (providedVersion == null) {
            LOG.debug("Cleanup dependency candidate ${trigger.groupId}:${trigger.artifactId}:$targetVersion does not provide $key")
            return null
        }

        if (ComparableVersion(providedVersion) < managedComparable) {
            LOG.debug("Cleanup rejected for $key: provided $providedVersion is older than ${managed.currentVersion}")
            return null
        }

        val distinctConsumerRoots = consumerPaths.map { it.first().artifact }.distinctBy { "${it.groupId}:${it.artifactId}" }
        val isSingleConsumerOrAllTrigger = distinctConsumerRoots.all { it.groupId == trigger.groupId && it.artifactId == trigger.artifactId }

        val consumerInfos = consumerPaths.map { path ->
            val root = path.first().artifact
            val isTrigger = root.groupId == trigger.groupId && root.artifactId == trigger.artifactId
            val pathDesc = path.joinToString(" -> ") { "${it.artifact.artifactId}:${it.artifact.version}" }
            ConsumerDependencyInfo(
                groupId = root.groupId,
                artifactId = root.artifactId,
                resolvedVersion = if (isTrigger) providedVersion else path.last().artifact.version,
                pathDescription = pathDesc
            )
        }

        return ManagedDependencyRemovalRecommendation(
            managedGroupId = managed.groupId,
            managedArtifactId = managed.artifactId,
            managedCurrentVersion = managed.currentVersion,
            triggerGroupId = trigger.groupId,
            triggerArtifactId = trigger.artifactId,
            triggerType = trigger.type,
            triggerCurrentVersion = trigger.currentVersion,
            triggerTargetVersion = targetVersion,
            transitiveVersionInTarget = providedVersion,
            consumers = consumerInfos,
            isSatisfiedAcrossAllConsumers = isSingleConsumerOrAllTrigger
        )
    }

    /**
     * Findet alle Konsumenten-Pfade einer verwalteten Abhängigkeit im aufgelösten Abhängigkeitsbaum des Projekts.
     *
     * @param mavenProject Das Maven-Projekt.
     * @param targetGroupId Die gesuchte Group-ID.
     * @param targetArtifactId Die gesuchte Artefakt-ID.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
     * @return Liste der gefundenen Pfade als Listen von [MavenArtifactNode].
     */
    internal fun findProjectConsumersForManaged(
        mavenProject: MavenProject,
        targetGroupId: String,
        targetArtifactId: String,
        indicator: ProgressIndicator? = null
    ): List<List<MavenArtifactNode>> {
        val result = mutableListOf<List<MavenArtifactNode>>()
        for (rootNode in mavenProject.dependencyTree) {
            indicator?.checkCanceled()
            findPathsToTarget(rootNode, targetGroupId, targetArtifactId, emptyList(), mutableSetOf(), result, indicator)
        }
        return result
    }

    /**
     * Durchsucht den Teilbaum rekursiv nach Pfaden zur Zielkoordinate.
     *
     * @param currentNode Der aktuelle Knoten im Abhängigkeitsbaum.
     * @param targetGroupId Die gesuchte Group-ID.
     * @param targetArtifactId Die gesuchte Artefakt-ID.
     * @param currentPath Der bisherige Pfad von der Wurzel.
     * @param visited Menge bereits besuchter Knoten zur Vermeidung von Zyklen.
     * @param result Die Ergebnisliste aller gefundenen Pfade.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
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

        for (child in currentNode.dependencies) {
            findPathsToTarget(child, targetGroupId, targetArtifactId, newPath, visited, result, indicator)
        }
    }

    /**
     * Liest die deklarierten `<dependencyManagement>`-Abhängigkeiten aus der `pom.xml` des Projekts.
     *
     * @param mavenProject Das Maven-Projekt.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
     * @return Liste der deklarierten verwalteten Abhängigkeiten.
     */
    internal fun collectManagedDependencies(
        mavenProject: MavenProject,
        indicator: ProgressIndicator? = null
    ): List<ManagedDependencyDeclaration> {
        val declarations = mutableListOf<ManagedDependencyDeclaration>()
        val effectiveProperties = mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }

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

                // BOM-Imports überspringen, da sie Sammlungen verwalten
                if (type.equals("pom", ignoreCase = true) && scope.equals("import", ignoreCase = true)) {
                    return@forEach
                }

                if (g.isNotEmpty() && a.isNotEmpty() && v.isNotEmpty()) {
                    val resolvedVersion = resolvePropertyPlaceholder(v, effectiveProperties)
                    declarations.add(
                        ManagedDependencyDeclaration(
                            groupId = g,
                            artifactId = a,
                            currentVersion = resolvedVersion.ifEmpty { v },
                            mavenProject = mavenProject
                        )
                    )
                }
            }
        }
        return declarations
    }

    /**
     * Sammelt alle Kandidaten für Versions-Updates (`<parent>` und direkte `<dependencies>`).
     *
     * @param mavenProject Das Maven-Projekt.
     * @param availableVersionsMap Bereits bekannte verfügbare Versionen.
     * @param indicator Optionaler Fortschrittsindikator für Statusdetails und Abbruch.
     * @return Liste der [TriggerCandidate] mit verfügbaren neueren Versionen.
     */
    internal fun collectTriggerCandidates(
        mavenProject: MavenProject,
        availableVersionsMap: Map<String, List<String>>,
        indicator: ProgressIndicator? = null
    ): List<TriggerCandidate> {
        val candidates = mutableListOf<TriggerCandidate>()
        val effectiveProperties = mavenProject.properties.entries.associate { (k, v) -> k.toString() to v.toString() }

        ApplicationManager.getApplication().runReadAction {
            indicator?.checkCanceled()
            val rootTag = (PsiManager.getInstance(project).findFile(mavenProject.file) as? XmlFile)?.document?.rootTag ?: return@runReadAction

            // 1. Parent POM
            val parentCandidate = extractParentCandidate(
                rootTag,
                effectiveProperties,
                mavenProject,
                availableVersionsMap,
                indicator
            )
            if (parentCandidate != null) {
                candidates.add(parentCandidate)
            }

            // 2. Direkte Abhängigkeiten
            val depsTag = rootTag.findFirstSubTag("dependencies")
            depsTag?.findSubTags("dependency")?.forEach { depTag ->
                indicator?.checkCanceled()
                val depCandidate = extractDependencyCandidate(
                    depTag,
                    effectiveProperties,
                    mavenProject,
                    availableVersionsMap,
                    indicator
                )
                if (depCandidate != null) {
                    candidates.add(depCandidate)
                }
            }
        }

        return candidates
    }

    /**
     * Extrahiert den Update-Kandidaten für das Parent-POM.
     *
     * @param rootTag Das Wurzelelement der Projekt-POM.
     * @param effectiveProperties Die aufgelösten effektiven Maven-Properties.
     * @param mavenProject Das zugehörige Maven-Projekt.
     * @param availableVersionsMap Bereits bekannte verfügbare Versionen.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
     * @return Der Parent-Kandidat oder `null`, wenn kein Update verfügbar ist.
     */
    private fun extractParentCandidate(
        rootTag: XmlTag,
        effectiveProperties: Map<String, String>,
        mavenProject: MavenProject,
        availableVersionsMap: Map<String, List<String>>,
        indicator: ProgressIndicator?
    ): TriggerCandidate? {
        val parentTag = rootTag.findFirstSubTag("parent") ?: return null
        val g = parentTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
        val a = parentTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
        val v = parentTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
        if (g.isNotEmpty() && a.isNotEmpty() && v.isNotEmpty()) {
            val resolvedV = resolvePropertyPlaceholder(v, effectiveProperties).ifEmpty { v }
            indicator?.text2 = "${mavenProject.mavenId}: $g:$a"
            val versions = getCandidateVersions(g, a, resolvedV, availableVersionsMap, indicator)
            if (versions.isNotEmpty()) {
                return TriggerCandidate(g, a, resolvedV, "parent", versions, mavenProject)
            }
        }
        return null
    }

    /**
     * Extrahiert den Update-Kandidaten für ein Dependency-Tag.
     *
     * @param depTag Das Dependency-Element der Projekt-POM.
     * @param effectiveProperties Die aufgelösten effektiven Maven-Properties.
     * @param mavenProject Das zugehörige Maven-Projekt.
     * @param availableVersionsMap Bereits bekannte verfügbare Versionen.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
     * @return Der Dependency-Kandidat oder `null`, wenn kein Update verfügbar ist.
     */
    private fun extractDependencyCandidate(
        depTag: XmlTag,
        effectiveProperties: Map<String, String>,
        mavenProject: MavenProject,
        availableVersionsMap: Map<String, List<String>>,
        indicator: ProgressIndicator?
    ): TriggerCandidate? {
        val g = depTag.findFirstSubTag("groupId")?.value?.text?.trim().orEmpty()
        val a = depTag.findFirstSubTag("artifactId")?.value?.text?.trim().orEmpty()
        val v = depTag.findFirstSubTag("version")?.value?.text?.trim().orEmpty()
        if (g.isNotEmpty() && a.isNotEmpty() && v.isNotEmpty()) {
            val resolvedV = resolvePropertyPlaceholder(v, effectiveProperties).ifEmpty { v }
            indicator?.text2 = "${mavenProject.mavenId}: $g:$a"
            val versions = getCandidateVersions(g, a, resolvedV, availableVersionsMap, indicator)
            if (versions.isNotEmpty()) {
                return TriggerCandidate(g, a, resolvedV, "dependency", versions, mavenProject)
            }
        }
        return null
    }

    /**
     * Ermittelt neuere Kandidatenversionen für ein Artefakt.
     *
     * @param groupId Die Group-ID.
     * @param artifactId Die Artefakt-ID.
     * @param currentVersion Die aktuelle Version.
     * @param availableVersionsMap Bereits bekannte verfügbare Versionen.
     * @param indicator Optionaler Fortschrittsindikator für Abbruchprüfungen.
     * @return Liste verfügbarer Versionen, die neuer als [currentVersion] sind.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun getCandidateVersions(
        groupId: String,
        artifactId: String,
        currentVersion: String,
        availableVersionsMap: Map<String, List<String>>,
        indicator: ProgressIndicator?
    ): List<String> {
        indicator?.checkCanceled()
        if (candidateVersionsProvider != null) {
            return candidateVersionsProvider.invoke(groupId, artifactId, currentVersion).also {
                indicator?.checkCanceled()
            }
        }

        val key = "$groupId:$artifactId"
        val knownList = availableVersionsMap[key]
        if (knownList != null) {
            val currentComp = ComparableVersion(currentVersion)
            LOG.debug("Cleanup reuses known versions for $key: ${summarizeForDebugLog(knownList)}")
            return knownList.filter {
                indicator?.checkCanceled()
                ComparableVersion(it) > currentComp
            }
        }

        return try {
            LOG.debug("Cleanup fetching candidate versions for $key")
            val apiService = DependencyApiService(project)
            val fetched = apiService.fetchAllVersions(groupId, artifactId)
            indicator?.checkCanceled()
            val currentComp = ComparableVersion(currentVersion)
            fetched.filter {
                indicator?.checkCanceled()
                ComparableVersion(it) > currentComp
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.debug("Could not fetch candidate versions for $groupId:$artifactId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Löst Platzhalter der Form `${property.name}` gegen die effektiven Maven-Properties auf.
     *
     * @param rawVersion Der Rohwert aus dem XML.
     * @param properties Die effektiven Properties des Maven-Projekts.
     * @return Die aufgelöste Version oder der Originalwert.
     */
    private fun resolvePropertyPlaceholder(rawVersion: String, properties: Map<String, String>): String {
        if (!rawVersion.startsWith("\${") || !rawVersion.endsWith("}")) {
            return rawVersion
        }
        val propName = rawVersion.removeSurrounding("\${", "}").trim()
        return properties[propName] ?: rawVersion
    }
}
