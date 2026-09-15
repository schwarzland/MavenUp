package de.schwarzland.mavenup.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.xml.XmlTag

/**
 * Definiert die verschiedenen Knotentypen innerhalb des Abhängigkeitshierarchie-Baums.
 */
enum class DependencyHierarchyNodeType {
    /** Root-Knoten für die abgefragte Managed Dependency bzw. das Plugin. */
    ROOT,

    /** Maven-Projekt bzw. Modul (`pom.xml`). */
    PROJECT,

    /** Deklarierte Parent-POM (`<parent>`). */
    PARENT_POM,

    /** BOM-Import im `<dependencyManagement>` (`<scope>import</scope>`). */
    BOM_IMPORT,

    /** Deklaration im `<dependencyManagement>`. */
    DEPENDENCY_MANAGEMENT,

    /** Deklaration im `<pluginManagement>`. */
    PLUGIN_MANAGEMENT,

    /** Direkte Abhängigkeit (`<dependencies><dependency>`). */
    DIRECT_DEPENDENCY,

    /** Direktes Plugin (`<build><plugins><plugin>`). */
    DIRECT_PLUGIN,

    /** Transitive Abhängigkeit im aufgelösten Abhängigkeitsbaum. */
    TRANSITIVE_DEPENDENCY
}

/**
 * Repräsentiert einen Knoten im Abhängigkeitshierarchie-Baum.
 *
 * @property type Der Typ des Hierarchieknotens.
 * @property groupId Group-ID der Komponente oder des Projekts.
 * @property artifactId Artefakt-ID der Komponente oder des Projekts.
 * @property version Die deklarierte oder aufgelöste Version.
 * @property rawVersion Die im XML angegebene Version (z. B. `${property.version}`).
 * @property propertyName Name des erkannten Versions-Property-Platzhalters.
 * @property scope Maven-Scope (z. B. `compile`, `test`, `import`).
 * @property isManaged Gibt an, ob die Version durch Dependency- oder Plugin-Management bestimmt wird.
 * @property pomFile Die zugehörige `pom.xml`-Datei zur Navigation im Editor.
 * @property xmlTag Das deklarierende XML-Tag zur punktgenauen Navigation.
 * @property children Untergeordnete Knoten im Hierarchiebaum.
 */
data class DependencyHierarchyNode(
    val type: DependencyHierarchyNodeType,
    val groupId: String,
    val artifactId: String,
    val version: String? = null,
    val rawVersion: String? = null,
    val propertyName: String? = null,
    val scope: String? = null,
    val isManaged: Boolean = false,
    val pomFile: VirtualFile? = null,
    val xmlTag: XmlTag? = null,
    val children: MutableList<DependencyHierarchyNode> = mutableListOf()
)
