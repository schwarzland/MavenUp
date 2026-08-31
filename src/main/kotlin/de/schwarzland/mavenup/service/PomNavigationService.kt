package de.schwarzland.mavenup.service

import de.schwarzland.mavenup.ui.MANAGED_PLUGIN
import de.schwarzland.mavenup.ui.MyMessageBundle
import de.schwarzland.mavenup.ui.PARENT_TYPE
import de.schwarzland.mavenup.ui.TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Findet Abhängigkeits-, Parent- und Plugin-Definitionen in den `pom.xml`-Dateien eines Projekts
 * und öffnet den Editor an der jeweiligen Definition.
 *
 * Der Service arbeitet ausschließlich über PSI und hält keinen veränderlichen UI-Zustand.
 *
 * @property project Das Projekt, dessen `pom.xml`-Dateien durchsucht werden.
 */
internal class PomNavigationService(private val project: Project) {

    /**
     * Sucht innerhalb eines XML-Parent-Tags nach einem Kind-Tag (z. B. `dependency` oder `plugin`)
     * das mit der angegebenen groupId und artifactId übereinstimmt.
     *
     * @param parentTag Das Tag, dessen Kind-Tags durchsucht werden.
     * @param tagName Der Name der zu prüfenden Kind-Tags.
     * @param groupId Die gesuchte Group-ID.
     * @param artifactId Die gesuchte Artefakt-ID.
     * @return Das passende Tag oder `null`.
     */
    private fun findTag(parentTag: XmlTag?, tagName: String, groupId: String, artifactId: String): XmlTag? {
        return parentTag?.findSubTags(tagName)?.find { tag ->
            val g = tag.findFirstSubTag("groupId")?.value?.text
            val a = tag.findFirstSubTag("artifactId")?.value?.text
            g == groupId && a == artifactId
        }
    }

    /**
     * Sucht nach einer Abhängigkeit in der `pom.xml`.
     * Unterstützt sowohl direkte Abhängigkeiten als auch Einträge im `dependencyManagement`.
     *
     * @param rootTag Das Root-Tag der `pom.xml`.
     * @param groupId Die gesuchte Group-ID.
     * @param artifactId Die gesuchte Artefakt-ID.
     * @param isManaged `true`, wenn nur im `dependencyManagement` gesucht werden soll.
     * @return Das passende `dependency`-Tag oder `null`.
     */
    internal fun findDependency(
        rootTag: XmlTag?,
        groupId: String,
        artifactId: String,
        isManaged: Boolean
    ): XmlTag? {
        if (!isManaged) {
            val dependenciesTag = rootTag?.findFirstSubTag("dependencies")
            val localDep = findTag(dependenciesTag, "dependency", groupId, artifactId)
            if (localDep != null) return localDep
        }

        val dmTag = rootTag?.findFirstSubTag("dependencyManagement")
        val dmDepsTag = dmTag?.findFirstSubTag("dependencies")
        return findTag(dmDepsTag, "dependency", groupId, artifactId)
    }

    /**
     * Sucht nach dem `<parent>`-Tag in der `pom.xml`, das mit der angegebenen groupId
     * und artifactId übereinstimmt.
     *
     * @param rootTag Das Root-Tag der `pom.xml`.
     * @param groupId Die gesuchte Group-ID.
     * @param artifactId Die gesuchte Artefakt-ID.
     * @return Das `<parent>`-Tag oder `null`, wenn keines passt.
     */
    internal fun findParent(rootTag: XmlTag?, groupId: String, artifactId: String): XmlTag? {
        val parentTag = rootTag?.findFirstSubTag("parent") ?: return null
        val g = parentTag.findFirstSubTag("groupId")?.value?.text
        val a = parentTag.findFirstSubTag("artifactId")?.value?.text
        return if (g == groupId && a == artifactId) parentTag else null
    }

    /**
     * Sucht nach einem Plugin in `<build><plugins>` oder `<build><pluginManagement>`.
     *
     * @param rootTag Das Root-Tag der `pom.xml`.
     * @param groupId Die gesuchte Group-ID.
     * @param artifactId Die gesuchte Artefakt-ID.
     * @param isManaged `true`, wenn nur im `pluginManagement` gesucht werden soll.
     * @return Das passende `plugin`-Tag oder `null`.
     */
    internal fun findPlugin(rootTag: XmlTag?, groupId: String, artifactId: String, isManaged: Boolean): XmlTag? {
        val buildTag = rootTag?.findFirstSubTag("build")
        if (!isManaged) {
            val pluginsTag = buildTag?.findFirstSubTag("plugins")
            val localPlugin = findTag(pluginsTag, "plugin", groupId, artifactId)
            if (localPlugin != null) return localPlugin
        }

        val pmTag = buildTag?.findFirstSubTag("pluginManagement")
        val pmPluginsTag = pmTag?.findFirstSubTag("plugins")
        return findTag(pmPluginsTag, "plugin", groupId, artifactId)
    }

    /**
     * Sucht die Definition einer Maven-Property in der `pom.xml`.
     *
     * Berücksichtigt sowohl das globale `<properties>`-Tag als auch `<properties>`-Blöcke
     * innerhalb von `<profiles><profile>`.
     *
     * @param rootTag Das Root-Tag der `pom.xml`.
     * @param propertyName Der Name der Property, optional in der Schreibweise `${name}`.
     * @return Das Tag der Property-Definition oder `null`, wenn die Property hier nicht definiert ist.
     */
    internal fun findProperty(rootTag: XmlTag?, propertyName: String): XmlTag? {
        val name = propertyName.trim().removeSurrounding("\${", "}").trim()
        if (name.isEmpty() || rootTag == null) return null

        rootTag.findFirstSubTag("properties")?.findFirstSubTag(name)?.let { return it }

        val profilesTag = rootTag.findFirstSubTag("profiles") ?: return null
        return profilesTag.findSubTags("profile")
            .firstNotNullOfOrNull { it.findFirstSubTag("properties")?.findFirstSubTag(name) }
    }

    /**
     * Öffnet den Editor und springt zur Definition der angegebenen Maven-Property in der `pom.xml`.
     *
     * Ist die Property in keiner `pom.xml` des Projekts definiert, wird auf die Navigation zur
     * Abhängigkeit selbst zurückgefallen.
     *
     * @param propertyName Der Name der Property, optional in der Schreibweise `${name}`.
     * @param groupId Die Group-ID der Abhängigkeit für den Fallback.
     * @param artifactId Die Artefakt-ID der Abhängigkeit für den Fallback.
     * @param type Der Typ der Abhängigkeit für den Fallback.
     */
    internal fun navigateToProperty(
        propertyName: String,
        groupId: String,
        artifactId: String,
        type: String = "dependency"
    ) {
        if (propertyName.isBlank()) {
            navigateToDependency(groupId, artifactId, type)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"),
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
                    val pomFile = mavenProject.file
                    val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                        val psiFile = PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                        findProperty(psiFile?.document?.rootTag, propertyName)
                    } ?: continue

                    openInEditor(pomFile, targetTag)
                    return
                }
                navigateToDependency(groupId, artifactId, type)
            }
        })
    }

    /**
     * Öffnet die angegebene Datei im Editor und positioniert den Cursor auf dem übergebenen Tag.
     *
     * @param pomFile Die zu öffnende `pom.xml`.
     * @param targetTag Das Tag, an dessen Position gesprungen wird.
     */
    private fun openInEditor(pomFile: VirtualFile, targetTag: XmlTag) {
        val offset = ApplicationManager.getApplication().runReadAction<Int> { targetTag.textOffset }
        ApplicationManager.getApplication().invokeLater {
            val descriptor = OpenFileDescriptor(project, pomFile, offset)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }

    /**
     * Öffnet den Editor und springt zur Definition der angegebenen Abhängigkeit in der `pom.xml`.
     *
     * @param groupId Die Group-ID der Abhängigkeit.
     * @param artifactId Die Artefakt-ID der Abhängigkeit.
     * @param type Der Typ der Abhängigkeit (Dependency, Parent, Plugin, verwaltet).
     */
    internal fun navigateToDependency(groupId: String, artifactId: String, type: String = "dependency") {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button"),
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
                    val pomFile = mavenProject.file
                    val psiFile = ApplicationManager.getApplication().runReadAction<XmlFile?> {
                        PsiManager.getInstance(project).findFile(pomFile) as? XmlFile
                    } ?: continue

                    val targetTag = ApplicationManager.getApplication().runReadAction<XmlTag?> {
                        val rootTag = psiFile.document?.rootTag
                        val managedDepType =
                            MyMessageBundle.message(TOOLWINDOW_MY_TOOL_WINDOW_TYPE_MANAGED_DEPENDENCY)
                        val isManaged = type == managedDepType || type == MANAGED_PLUGIN

                        if (type == PARENT_TYPE) {
                            findParent(rootTag, groupId, artifactId)
                        } else if (type == "dependency" || type == managedDepType) {
                            findDependency(rootTag, groupId, artifactId, isManaged)
                        } else {
                            findPlugin(rootTag, groupId, artifactId, isManaged)
                        }
                    }

                    if (targetTag != null) {
                        openInEditor(pomFile, targetTag)
                        return
                    }
                }
            }
        })
    }
}
