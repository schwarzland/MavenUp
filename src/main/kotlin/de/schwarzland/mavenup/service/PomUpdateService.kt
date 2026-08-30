package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.ui.MANAGED_PLUGIN
import de.schwarzland.mavenup.ui.MyMessageBundle
import de.schwarzland.mavenup.ui.PARENT_TYPE
import de.schwarzland.mavenup.ui.TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.project.MavenProject

/**
 * Wendet ausgewählte Versions-Updates auf die `pom.xml`-Dateien eines Projekts an und speichert
 * die Änderungen bei Bedarf auf die Festplatte.
 *
 * Der Service arbeitet ausschließlich über PSI und hält keinen veränderlichen UI-Zustand.
 *
 * @property project Das Projekt, dessen `pom.xml`-Dateien geändert werden.
 */
internal class PomUpdateService(private val project: Project) {

    /**
     * Wendet die ausgewählten Updates auf die `pom.xml` des angegebenen Maven-Projekts an.
     *
     * @param mavenProject Das Maven-Projekt, dessen `pom.xml` aktualisiert wird.
     * @param updates Die anzuwendenden Updates.
     */
    internal fun applyUpdateToPom(
        mavenProject: MavenProject,
        updates: List<DependencyUpdate>
    ) {
        val pomFile = mavenProject.file
        val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
            PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
        } ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val documentElement = psiFile.document?.rootTag ?: return@runWriteCommandAction
            val propertiesTag = documentElement.findFirstSubTag("properties")

            updates.forEach { update ->
                val managedDependencyType = MyMessageBundle.message(
                    TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY
                )
                when (update.type) {
                    PARENT_TYPE -> {
                        updateParent(documentElement, update, propertiesTag)
                    }
                    "dependency" -> {
                        updateDependencies(documentElement, update, propertiesTag)
                    }
                    managedDependencyType -> {
                        val updated = updateDependencies(documentElement, update, propertiesTag)
                        if (!updated) {
                            addManagedDependency(documentElement, update)
                        }
                    }
                    "plugin", MANAGED_PLUGIN -> {
                        updatePlugins(documentElement, update, propertiesTag)
                    }
                }
            }
        }
    }

    /**
     * Schreibt die zuvor über PSI geänderten `pom.xml`-Dateien auf die Festplatte.
     *
     * PSI-/Document-Änderungen liegen zunächst nur im Speicher vor. Der anschließende
     * Maven-Sync (`forceUpdateAllProjectsOrFindAllAvailablePomFiles`) liest die POM-Dateien
     * jedoch von der Festplatte neu ein. Ohne vorheriges Speichern würde Maven den alten,
     * unveränderten Inhalt importieren und das Projekt bliebe unsynchronisiert. Das Speichern
     * erfolgt synchron auf dem EDT, damit es vor dem Auslösen des Sync abgeschlossen ist.
     *
     * @param pomFiles Die POM-Dateien, deren offene Documents gespeichert werden sollen.
     */
    internal fun persistPomChanges(pomFiles: List<VirtualFile>) {
        ApplicationManager.getApplication().invokeAndWait {
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            val fileDocumentManager = FileDocumentManager.getInstance()
            pomFiles.forEach { pomFile ->
                val document = fileDocumentManager.getDocument(pomFile) ?: return@forEach
                psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
                fileDocumentManager.saveDocument(document)
            }
        }
    }

    /**
     * Aktualisiert Abhängigkeitsversionen in `<dependencies>` und `<dependencyManagement>`
     * des angegebenen POM-Root-Tags für ein einzelnes Update.
     *
     * @param documentElement Das Root-Tag der `pom.xml`.
     * @param update Das anzuwendende Update.
     * @param propertiesTag Das `<properties>`-Tag für Property-basierte Versionsupdates.
     * @return `true`, wenn mindestens ein passender Eintrag gefunden und aktualisiert wurde.
     */
    private fun updateDependencies(
        documentElement: XmlTag,
        update: DependencyUpdate,
        propertiesTag: XmlTag?
    ): Boolean {
        val dependenciesTag = documentElement.findFirstSubTag("dependencies")
        val dependencyManagementTag = documentElement.findFirstSubTag("dependencyManagement")
        val dmDependenciesTag = dependencyManagementTag?.findFirstSubTag("dependencies")

        var updated = false
        // Search in <dependencies>
        dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
            updated = updateIfMatch(depTag, update, propertiesTag) || updated
        }
        // Search in <dependencyManagement><dependencies>
        dmDependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
            updated = updateIfMatch(depTag, update, propertiesTag) || updated
        }
        return updated
    }

    /**
     * Fügt eine neue verwaltete Abhängigkeit in `<dependencyManagement><dependencies>` hinzu und pinnt
     * damit die Version einer bisher nicht in der `pom.xml` deklarierten (transitiven) Abhängigkeit.
     *
     * Fehlende Container-Tags (`<dependencyManagement>`, `<dependencies>`) werden bei Bedarf erzeugt.
     * Die Abhängigkeit wird samt optional vorangestelltem Kommentar aus Text erzeugt (Kommentar auf
     * eigener Zeile direkt hinter dem öffnenden `<dependency>`) und anschließend neu formatiert, damit
     * die Einrückung zur Datei passt. Ob und mit welchen Kennungen der Kommentar eingefügt wird, richtet
     * sich nach der Einstellung [MavenUpSettings.State.vulnerabilityCommentMode].
     *
     * @param documentElement Das Root-Tag der `pom.xml`.
     * @param update Das anzuwendende Update mit der zu pinnenden Zielversion.
     */
    internal fun addManagedDependency(documentElement: XmlTag, update: DependencyUpdate) {
        val dependencyManagementTag = documentElement.findFirstSubTag("dependencyManagement")
            ?: documentElement.addSubTag(
                documentElement.createChildTag("dependencyManagement", null, "", false), false
            )
        val dependenciesTag = dependencyManagementTag.findFirstSubTag("dependencies")
            ?: dependencyManagementTag.addSubTag(
                dependencyManagementTag.createChildTag("dependencies", null, "", false), false
            )
        val settingsState = MavenUpSettings.getInstance().state
        val commentMode = settingsState.vulnerabilityCommentMode
        val dependencyXml = buildString {
            append("<dependency>\n")
            if (commentMode != VulnerabilityCommentMode.NONE) {
                append("<!-- ")
                    .append(
                        managedDependencyCommentText(
                            update,
                            commentMode,
                            settingsState.vulnerabilityCommentPrefix,
                            settingsState.vulnerabilityCommentMaxIds
                        )
                    )
                    .append(" -->\n")
            }
            append("<groupId>").append(update.groupId).append("</groupId>\n")
            append("<artifactId>").append(update.artifactId).append("</artifactId>\n")
            append("<version>").append(update.newVersion).append("</version>\n")
            append("</dependency>")
        }
        val dependencyTag = XmlElementFactory.getInstance(project).createTagFromText(dependencyXml)
        val added = dependenciesTag.addSubTag(dependencyTag, false)
        CodeStyleManager.getInstance(project).reformat(added)
    }

    /**
     * Baut den Kommentartext für eine neu angelegte verwaltete Abhängigkeit.
     *
     * Stellt dem Text den konfigurierten [prefix] voran und listet je nach [mode] die primären
     * Advisory-IDs, deren Aliase (z. B. `CVE-…`) oder alle bekannten Kennungen der behobenen
     * Sicherheitswarnungen auf. Überschreitet die Anzahl der Kennungen [maxIds], werden nur die ersten
     * [maxIds] Kennungen geschrieben und die übrigen durch einen „and more"-Hinweis ersetzt; `0` hebt
     * die Begrenzung auf. Werden keine Kennungen geschrieben, entfällt ein abschließender Doppelpunkt
     * des Präfixes; ist auch das Präfix leer, wird ein generischer Hinweistext verwendet. Doppelte
     * Bindestriche werden entschärft, da sie in XML-Kommentaren unzulässig sind.
     *
     * @param update Das anzuwendende Update mit den Kennungen der behobenen Sicherheitswarnungen.
     * @param mode Der konfigurierte Kommentarmodus; [VulnerabilityCommentMode.NONE] und
     * [VulnerabilityCommentMode.TEXT_ONLY] listen keine Kennungen auf.
     * @param prefix Der konfigurierte Präfixtext vor den Kennungen.
     * @param maxIds Die Höchstzahl der aufgelisteten Kennungen; `0` bedeutet „unbegrenzt".
     * @return Der (entschärfte) Kommentartext ohne die umschließenden Kommentar-Marker.
     */
    internal fun managedDependencyCommentText(
        update: DependencyUpdate,
        mode: VulnerabilityCommentMode,
        prefix: String,
        maxIds: Int
    ): String {
        val ids = when (mode) {
            VulnerabilityCommentMode.NONE, VulnerabilityCommentMode.TEXT_ONLY -> emptyList()
            VulnerabilityCommentMode.ADVISORY_IDS -> update.fixedVulnerabilities
            VulnerabilityCommentMode.ALIASES -> update.fixedVulnerabilityAliases
            VulnerabilityCommentMode.ALL_IDS ->
                (update.fixedVulnerabilities + update.fixedVulnerabilityAliases).distinct()
        }
        val trimmedPrefix = prefix.trim()
        val text = if (ids.isEmpty()) {
            trimmedPrefix.trimEnd(':').trim().ifEmpty {
                MyMessageBundle.message("pom.comment.managedDependencyNoIds")
            }
        } else {
            listOf(trimmedPrefix, joinVulnerabilityIds(ids, maxIds))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }
        return text.replace("--", "- -")
    }

    /**
     * Verkettet die Kennungen der behobenen Sicherheitswarnungen unter Beachtung der Höchstzahl.
     *
     * @param ids Die zu schreibenden Kennungen; darf nicht leer sein.
     * @param maxIds Die Höchstzahl der aufgelisteten Kennungen; Werte `<= 0` heben die Begrenzung auf.
     * @return Die kommaseparierte Liste, bei Überschreitung gefolgt von einem „and more"-Hinweis.
     */
    private fun joinVulnerabilityIds(ids: List<String>, maxIds: Int): String {
        if (maxIds <= 0 || ids.size <= maxIds) return ids.joinToString(", ")
        return ids.take(maxIds).joinToString(", ") + " " + MyMessageBundle.message("pom.comment.andMore")
    }

    /**
     * Aktualisiert die Version im `<parent>`-Tag der `pom.xml` für ein Parent-Update.
     *
     * @param documentElement Das Root-Tag der `pom.xml`.
     * @param update Das anzuwendende Update.
     * @param propertiesTag Das `<properties>`-Tag für Property-basierte Versionsupdates.
     */
    private fun updateParent(
        documentElement: XmlTag,
        update: DependencyUpdate,
        propertiesTag: XmlTag?
    ) {
        val parentTag = documentElement.findFirstSubTag("parent") ?: return
        val g = parentTag.findFirstSubTag("groupId")?.value?.text
        val a = parentTag.findFirstSubTag("artifactId")?.value?.text
        if (g == update.groupId && a == update.artifactId) {
            updateXmlTagVersion(parentTag, update.newVersion, propertiesTag)
        }
    }

    /**
     * Aktualisiert Plugin-Versionen in `<build><plugins>` und `<build><pluginManagement>`
     * des angegebenen POM-Root-Tags für ein einzelnes Update.
     *
     * @param documentElement Das Root-Tag der `pom.xml`.
     * @param update Das anzuwendende Update.
     * @param propertiesTag Das `<properties>`-Tag für Property-basierte Versionsupdates.
     */
    private fun updatePlugins(
        documentElement: XmlTag,
        update: DependencyUpdate,
        propertiesTag: XmlTag?
    ) {
        val buildTag = documentElement.findFirstSubTag("build")
        val pluginsTag = buildTag?.findFirstSubTag("plugins")
        val pluginManagementTag = buildTag?.findFirstSubTag("pluginManagement")
        val pmPluginsTag = pluginManagementTag?.findFirstSubTag("plugins")

        // Search in <build><plugins>
        pluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
            updateIfMatch(pluginTag, update, propertiesTag)
        }
        // Search in <build><pluginManagement><plugins>
        pmPluginsTag?.findSubTags("plugin")?.forEach { pluginTag ->
            updateIfMatch(pluginTag, update, propertiesTag)
        }
    }

    /**
     * Prüft, ob ein XML-Tag mit groupId und artifactId des Updates übereinstimmt,
     * und aktualisiert in diesem Fall die Version.
     *
     * @param tag Das zu prüfende `dependency`- oder `plugin`-Tag.
     * @param update Das anzuwendende Update.
     * @param propertiesTag Das `<properties>`-Tag für Property-basierte Versionsupdates.
     * @return `true`, wenn das Tag übereinstimmte und aktualisiert wurde.
     */
    private fun updateIfMatch(
        tag: XmlTag,
        update: DependencyUpdate,
        propertiesTag: XmlTag?
    ): Boolean {
        val g = tag.findFirstSubTag("groupId")?.value?.text
        val a = tag.findFirstSubTag("artifactId")?.value?.text

        return if (g == update.groupId && a == update.artifactId) {
            updateXmlTagVersion(tag, update.newVersion, propertiesTag)
            true
        } else {
            false
        }
    }

    /**
     * Aktualisiert die Versionsnummer in einem XML-Tag. Berücksichtigt dabei, ob die Version
     * direkt oder über eine Maven-Property definiert ist.
     *
     * @param tag Das Tag, dessen Version aktualisiert wird.
     * @param newVersion Die neue Version.
     * @param propertiesTag Das `<properties>`-Tag für Property-basierte Versionsupdates.
     */
    internal fun updateXmlTagVersion(tag: XmlTag, newVersion: String, propertiesTag: XmlTag?) {
        val versionTag = tag.findFirstSubTag("version")
        if (versionTag != null) {
            // Use versionTag.value.trimmedText for recognition
            val versionContent = versionTag.value.trimmedText

            if (versionContent.startsWith("\${") && versionContent.endsWith("}")) {
                val propertyName = versionContent.substring(2, versionContent.length - 1)
                val propertyTag = propertiesTag?.findFirstSubTag(propertyName)
                if (propertyTag != null) {
                    propertyTag.value.text = newVersion
                    return // Property updated, don't overwrite version tag
                }
            }
            // Fallback or direct update: overwrite version tag
            versionTag.value.text = newVersion
        } else {
            val newTag = tag.createChildTag("version", null, newVersion, false)
            tag.addSubTag(newTag, false)
        }
    }
}
