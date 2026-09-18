@file:Suppress("UNCHECKED_CAST")

package de.schwarzland.mavenup

import de.schwarzland.mavenup.model.VulnerabilityAdvisory
import de.schwarzland.mavenup.model.VulnerabilitySeverity
import de.schwarzland.mavenup.model.DependencyUpdate
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.MavenRepositoryBrowser
import de.schwarzland.mavenup.service.VersionAutoSelectionMode
import de.schwarzland.mavenup.ui.DependencyContextMenuTarget
import de.schwarzland.mavenup.ui.buildMavenRepositoryUrl
import de.schwarzland.mavenup.ui.GROUP_ID_COLUMN
import de.schwarzland.mavenup.ui.ARTIFACT_ID_COLUMN
import de.schwarzland.mavenup.ui.MavenUpWindowFactory
import de.schwarzland.mavenup.ui.MANAGED_DEPENDENCY
import de.schwarzland.mavenup.ui.MANAGED_PLUGIN
import de.schwarzland.mavenup.ui.PARENT_TYPE
import de.schwarzland.mavenup.ui.TransitiveVulnerabilitiesView
import de.schwarzland.mavenup.ui.UpdateConfirmationDialog
import de.schwarzland.mavenup.service.RefreshSnapshotCollector
import de.schwarzland.mavenup.ui.MyMessageBundle
import de.schwarzland.mavenup.ui.RefreshSnapshot
import de.schwarzland.mavenup.ui.VulnerabilityOrigin
import de.schwarzland.mavenup.ui.buildVulnerabilityCell
import de.schwarzland.mavenup.ui.canCheckVulnerabilities
import de.schwarzland.mavenup.ui.CHECK_VULNERABILITIES_TOOLTIP_DIRECT_ALL_SOURCES_KEY
import de.schwarzland.mavenup.ui.CHECK_VULNERABILITIES_TOOLTIP_DIRECT_OSV_ONLY_KEY
import de.schwarzland.mavenup.ui.CHECK_VULNERABILITIES_TOOLTIP_TRANSITIVE_ALL_SOURCES_KEY
import de.schwarzland.mavenup.ui.CHECK_VULNERABILITIES_TOOLTIP_TRANSITIVE_OSV_ONLY_KEY
import de.schwarzland.mavenup.ui.EMPTY_TEXT_KEY_NO_DEPENDENCIES
import de.schwarzland.mavenup.ui.EMPTY_TEXT_KEY_NO_MATCHES
import de.schwarzland.mavenup.ui.EMPTY_TEXT_KEY_REFRESHING
import de.schwarzland.mavenup.ui.EMPTY_TEXT_KEY_SEARCHING
import de.schwarzland.mavenup.ui.dependencyTableEmptyTextKey
import de.schwarzland.mavenup.ui.vulnerabilitySummary
import de.schwarzland.mavenup.ui.vulnerabilityCellComparator
import de.schwarzland.mavenup.ui.VersionUpdateArrowIcon
import de.schwarzland.mavenup.ui.TriStateFilter
import de.schwarzland.mavenup.ui.PendingChangesFilter
import de.schwarzland.mavenup.ui.VulnerabilityFilter
import de.schwarzland.mavenup.ui.sortableHeaderIcon
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.application.ReadAction
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.table.JBTable
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import java.awt.Container
import javax.swing.JLabel
import javax.swing.table.DefaultTableModel
import java.util.concurrent.TimeUnit
import javax.swing.table.TableRowSorter

class MavenUpWindowFactoryTest : BasePlatformTestCase() {

    /**
     * Registriert ein Test-Tool-Window, baut den Plugin-Inhalt hinein und führt [block] mit dessen
     * [ContentManager] aus. Das Tool Window wird anschließend wieder abgemeldet.
     */
    @Suppress("OverrideOnly", "DEPRECATION")
    private fun withToolWindowContents(block: (ContentManager) -> Unit) {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = manager.registerToolWindow(
            RegisterToolWindowTask(
                id = "TestWindow",
                anchor = ToolWindowAnchor.BOTTOM,
                canCloseContent = true
            )
        )
        try {
            MavenUpWindowFactory().createToolWindowContent(project, toolWindow)
            block(toolWindow.contentManager)
        } finally {
            manager.unregisterToolWindow("TestWindow")
        }
    }

    fun testVulnerabilityCheckAvailabilityDuringRefreshAndUpdateCheck() {
        assertTrue(canCheckVulnerabilities(isRefreshing = false, isUpdating = false))
        assertFalse(canCheckVulnerabilities(isRefreshing = true, isUpdating = false))
        assertFalse(canCheckVulnerabilities(isRefreshing = false, isUpdating = true))
        assertFalse(canCheckVulnerabilities(isRefreshing = true, isUpdating = true))
    }

    fun testDependencyTableEmptyTextKeyPrefersRunningOperations() {
        assertEquals(
            EMPTY_TEXT_KEY_SEARCHING,
            dependencyTableEmptyTextKey(isRefreshing = true, isSearchingVersions = true, hasLoadedRows = true)
        )
        assertEquals(
            EMPTY_TEXT_KEY_SEARCHING,
            dependencyTableEmptyTextKey(isRefreshing = false, isSearchingVersions = true, hasLoadedRows = false)
        )
        assertEquals(
            EMPTY_TEXT_KEY_REFRESHING,
            dependencyTableEmptyTextKey(isRefreshing = true, isSearchingVersions = false, hasLoadedRows = true)
        )
    }

    fun testDependencyTableEmptyTextKeyDistinguishesFilterAndMissingData() {
        assertEquals(
            EMPTY_TEXT_KEY_NO_MATCHES,
            dependencyTableEmptyTextKey(isRefreshing = false, isSearchingVersions = false, hasLoadedRows = true)
        )
        assertEquals(
            EMPTY_TEXT_KEY_NO_DEPENDENCIES,
            dependencyTableEmptyTextKey(isRefreshing = false, isSearchingVersions = false, hasLoadedRows = false)
        )
    }

    fun testDependencyTableEmptyTextKeysResolveToMessages() {
        listOf(
            EMPTY_TEXT_KEY_SEARCHING,
            EMPTY_TEXT_KEY_REFRESHING,
            EMPTY_TEXT_KEY_NO_MATCHES,
            EMPTY_TEXT_KEY_NO_DEPENDENCIES
        ).forEach { key ->
            assertTrue(key, MyMessageBundle.message(key).isNotBlank())
        }
    }

    fun testVulnerabilityColumnIsAssociatedWithCurrentVersion() {
        val table = findTable(MavenUpWindowFactory().MyToolWindow(project).getContent())

        assertNotNull(table)
        assertEquals("Vulnerabilities", table!!.model.getColumnName(4))
        assertEquals("Current Version", table.model.getColumnName(5))
        assertEquals("New Version", table.model.getColumnName(6))
        assertFalse(table.model.isCellEditable(0, 4))
        assertTrue(table.model.isCellEditable(0, 6))
    }

    fun testColumnHeaderSortingCyclesAscendingDescendingPomOrder() {
        val table = findTable(MavenUpWindowFactory().MyToolWindow(project).getContent())
        assertNotNull(table)

        val model = table!!.model as DefaultTableModel
        model.addRow(arrayOf<Any?>("org.b", "b-lib", null, "dependency", null, "1.0.0", emptyList<String>()))
        model.addRow(arrayOf<Any?>("org.a", "a-lib", null, "dependency", null, "2.0.0", emptyList<String>()))

        @Suppress("UNCHECKED_CAST")
        val sorter = table.rowSorter as TableRowSorter<DefaultTableModel>

        // Current-Version- und New-Version-Spalte sind nicht sortierbar; die Vulnerabilities-Spalte ist sortierbar.
        assertTrue(sorter.isSortable(4))
        assertFalse(sorter.isSortable(5))
        assertFalse(sorter.isSortable(6))
        assertTrue(sorter.isSortable(0))

        // Erster Klick: aufsteigend -> org.a vor org.b.
        sorter.toggleSortOrder(0)
        assertEquals(javax.swing.SortOrder.ASCENDING, sorter.sortKeys.first().sortOrder)
        assertEquals("org.a", table.getValueAt(0, 0))

        // Zweiter Klick: absteigend -> org.b vor org.a.
        sorter.toggleSortOrder(0)
        assertEquals(javax.swing.SortOrder.DESCENDING, sorter.sortKeys.first().sortOrder)
        assertEquals("org.b", table.getValueAt(0, 0))

        // Dritter Klick: unsortiert -> ursprüngliche pom.xml-Reihenfolge.
        sorter.toggleSortOrder(0)
        assertTrue(sorter.sortKeys.isEmpty())
        assertEquals("org.b", table.getValueAt(0, 0))
    }

    fun testVulnerabilityCellComparatorSortsBySeverityThenCount() {
        fun cell(coordinate: String, vararg severities: VulnerabilitySeverity) =
            buildVulnerabilityCell(
                coordinate,
                mapOf(coordinate to severities.mapIndexed { index, severity ->
                    VulnerabilityAdvisory(id = "CVE-$coordinate-$index", severity = severity, sources = setOf("OSV"))
                }),
                emptySet()
            )

        val empty = buildVulnerabilityCell("org:empty:1.0.0", emptyMap(), emptySet())
        val lowSingle = cell("org:low:1.0.0", VulnerabilitySeverity.LOW)
        val highSingle = cell("org:high1:1.0.0", VulnerabilitySeverity.HIGH)
        val highDouble = cell("org:high2:1.0.0", VulnerabilitySeverity.HIGH, VulnerabilitySeverity.LOW)
        val critical = cell("org:critical:1.0.0", VulnerabilitySeverity.CRITICAL)

        // Primär nach Kritikalität: leer < LOW < HIGH < CRITICAL.
        assertTrue(vulnerabilityCellComparator.compare(empty, lowSingle) < 0)
        assertTrue(vulnerabilityCellComparator.compare(lowSingle, highSingle) < 0)
        assertTrue(vulnerabilityCellComparator.compare(highSingle, critical) < 0)

        // Sekundär nach Anzahl bei gleichem höchsten Schweregrad: eine < zwei Warnungen.
        assertTrue(vulnerabilityCellComparator.compare(highSingle, highDouble) < 0)
        assertEquals(0, vulnerabilityCellComparator.compare(highSingle, highSingle))

        // Nicht-Zellen-Werte (z. B. null) gelten als am wenigsten kritisch.
        assertTrue(vulnerabilityCellComparator.compare(null, lowSingle) < 0)

        val sorted = listOf(critical, empty, highDouble, lowSingle, highSingle)
            .sortedWith(vulnerabilityCellComparator)
        assertEquals(listOf(empty, lowSingle, highSingle, highDouble, critical), sorted)
    }

    fun testCurrentVersionColumnIsNotSortable() {
        val table = findTable(MavenUpWindowFactory().MyToolWindow(project).getContent())
        assertNotNull(table)

        val model = table!!.model as DefaultTableModel
        model.addRow(arrayOf<Any?>("org.x", "x-lib", null, "dependency", null, "1.9.0", emptyList<String>()))
        model.addRow(arrayOf<Any?>("org.y", "y-lib", null, "dependency", null, "1.10.0", emptyList<String>()))

        @Suppress("UNCHECKED_CAST")
        val sorter = table.rowSorter as TableRowSorter<DefaultTableModel>
        assertFalse(sorter.isSortable(5))

        // Ein Klick auf die Kopfzeile ändert die Reihenfolge nicht.
        sorter.toggleSortOrder(5)
        assertTrue(sorter.sortKeys.isEmpty())
        assertEquals("1.9.0", table.getValueAt(0, 5))
        assertEquals("1.10.0", table.getValueAt(1, 5))
    }

    fun testToolWindowIconResourceIsAvailable() {
        // Das eigene Tool-Window-Icon muss als Ressource im Klassenpfad vorhanden sein,
        // damit die in plugin.xml referenzierte Datei zur Laufzeit geladen werden kann.
        assertNotNull(
            "Icon /icons/mavenUpToolWindow.svg fehlt im Klassenpfad",
            javaClass.getResource("/icons/mavenUpToolWindow.svg")
        )
        assertNotNull(
            "Dark-Variante /icons/mavenUpToolWindow_dark.svg fehlt im Klassenpfad",
            javaClass.getResource("/icons/mavenUpToolWindow_dark.svg")
        )
    }

    fun testSortableHeaderIconReflectsSortState() {
        // Nicht sortierbare Spalten erhalten kein Icon.
        assertNull(sortableHeaderIcon(sortable = false, sortOrder = null))
        assertNull(sortableHeaderIcon(sortable = false, sortOrder = javax.swing.SortOrder.ASCENDING))

        // Sortierbare, aber unsortierte Spalten erhalten einen (gedämpften) Doppelpfeil.
        assertNotNull(sortableHeaderIcon(sortable = true, sortOrder = null))
        assertNotNull(sortableHeaderIcon(sortable = true, sortOrder = javax.swing.SortOrder.UNSORTED))

        // Aktive Sortierung zeigt die passenden Richtungspfeile.
        assertSame(
            AllIcons.General.ArrowUp,
            sortableHeaderIcon(sortable = true, sortOrder = javax.swing.SortOrder.ASCENDING)
        )
        assertSame(
            AllIcons.General.ArrowDown,
            sortableHeaderIcon(sortable = true, sortOrder = javax.swing.SortOrder.DESCENDING)
        )
    }

    fun testTransitiveVulnerabilitiesAreIncludedAndMarkedInDependencyCell() {
        val directCoordinate = "com.example:direct:1.0.0"
        val transitiveCoordinate = "com.example:transitive:2.0.0"
        val directAdvisory = VulnerabilityAdvisory(
            id = "CVE-DIRECT",
            severity = VulnerabilitySeverity.MEDIUM,
            sources = setOf("OSV")
        )
        val transitiveAdvisory = VulnerabilityAdvisory(
            id = "CVE-TRANSITIVE",
            severity = VulnerabilitySeverity.HIGH,
            sources = setOf("OSV")
        )

        val cell = buildVulnerabilityCell(
            directCoordinate,
            mapOf(
                directCoordinate to listOf(directAdvisory),
                transitiveCoordinate to listOf(transitiveAdvisory)
            ),
            setOf(transitiveCoordinate)
        )

        assertEquals(2, cell.allAdvisories.size)
        assertEquals(1, cell.transitiveAdvisoryCount)
        assertTrue(cell.hasDirectAdvisories)
        assertTrue(cell.hasTransitiveAdvisories)
        assertEquals("2 (1 transitive, HIGH)", vulnerabilitySummary(cell))
        assertEquals(listOf(directAdvisory), cell.detailFindings()[directCoordinate])
        assertEquals(
            listOf(transitiveAdvisory),
            cell.detailFindings()[transitiveCoordinate]
        )
        assertEquals(VulnerabilityOrigin.DIRECT, cell.detailOrigins()[directCoordinate])
        assertEquals(VulnerabilityOrigin.TRANSITIVE, cell.detailOrigins()[transitiveCoordinate])

        val transitiveOnly = buildVulnerabilityCell(
            directCoordinate,
            mapOf(transitiveCoordinate to listOf(transitiveAdvisory)),
            setOf(transitiveCoordinate)
        )
        assertFalse(transitiveOnly.hasDirectAdvisories)
        assertTrue(transitiveOnly.hasTransitiveAdvisories)

        val directOnly = buildVulnerabilityCell(
            directCoordinate,
            mapOf(directCoordinate to listOf(directAdvisory)),
            setOf(transitiveCoordinate)
        )
        assertTrue(directOnly.hasDirectAdvisories)
        assertFalse(directOnly.hasTransitiveAdvisories)

        val clean = buildVulnerabilityCell(directCoordinate, emptyMap(), emptySet())
        assertFalse(clean.hasDirectAdvisories)
        assertFalse(clean.hasTransitiveAdvisories)
    }

    fun testTransitiveCoordinateDeclaredInPomIsMarkedInDetailOrigins() {
        val directCoordinate = "com.example:direct:1.0.0"
        val transitiveCoordinate = "com.example:transitive:2.0.0"
        val advisory = VulnerabilityAdvisory(
            id = "CVE-TRANSITIVE",
            severity = VulnerabilitySeverity.HIGH,
            sources = setOf("OSV")
        )

        val declared = buildVulnerabilityCell(
            directCoordinate,
            mapOf(transitiveCoordinate to listOf(advisory)),
            setOf(transitiveCoordinate),
            setOf(transitiveCoordinate)
        )
        assertEquals(
            "Der Komponentenname darf kein Markierungs-Suffix tragen",
            listOf(advisory),
            declared.detailFindings()[transitiveCoordinate]
        )
        assertEquals(
            "Eine zugleich direkt deklarierte transitive Koordinate muss gesondert eingeordnet werden",
            VulnerabilityOrigin.TRANSITIVE_DECLARED,
            declared.detailOrigins()[transitiveCoordinate]
        )

        val onlyTransitive = buildVulnerabilityCell(
            directCoordinate,
            mapOf(transitiveCoordinate to listOf(advisory)),
            setOf(transitiveCoordinate),
            setOf(directCoordinate)
        )
        assertEquals(
            "Eine rein transitive Koordinate bleibt als transitiv eingeordnet",
            VulnerabilityOrigin.TRANSITIVE,
            onlyTransitive.detailOrigins()[transitiveCoordinate]
        )
    }

    fun testShouldBeAvailableOnlyWhenMavenProjectsExist() {
        val factory = MavenUpWindowFactory()

        // Standardmäßig sollten keine Maven-Projekte in einem leeren Testprojekt vorhanden sein
        assertFalse("ToolWindow sollte ohne Maven-Projekte nicht verfügbar sein", factory.shouldBeAvailable(project))
    }

    fun testToolWindowContentCreation() {
        withToolWindowContents { contentManager ->
            assertTrue("Content sollte zum ToolWindow hinzugefügt worden sein", contentManager.contentCount > 0)
        }
    }

    fun testManagedEntriesBulkMenuIsPresentInToolbar() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)
        val managedEntriesGroup = toolWindowInstance.topToolbarActions()
            .firstOrNull { it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.managedEntries.group.button") }
            as? DefaultActionGroup

        assertNotNull("Das \"Managed Entries\"-Untermenü sollte vorhanden sein", managedEntriesGroup)
        assertTrue(
            "Das Untermenü sollte die beiden Managed-Entries-Aktionen enthalten",
            managedEntriesGroup!!.childActionsOrStubs
                .map { it.templatePresentation.text }
                .containsAll(
                    listOf(
                        MyMessageBundle.message("toolwindow.MyToolWindow.managedEntries.removeManagedDependencies.button"),
                        MyMessageBundle.message("toolwindow.MyToolWindow.managedEntries.removeManagedPlugins.button")
                    )
                )
        )
    }

    fun testRefreshSnapshotCollectionRunsOutsideEdt() {
        val collector = RefreshSnapshotCollector(project)

        val snapshot: RefreshSnapshot = ReadAction.nonBlocking<RefreshSnapshot> {
            collector.collectRefreshSnapshot("managed dependency")
        }.submit(AppExecutorUtil.getAppExecutorService()).get(5, TimeUnit.SECONDS)

        assertNotNull(snapshot)
        assertTrue(snapshot.rows.isEmpty())
    }

    fun testSelectedUpdatesUseCachedRefreshData() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)
        val key = "com.example:example-core"
        val fields = toolWindowInstance.javaClass
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindowInstance) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindowInstance) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownTypes = fields.getDeclaredField("knownTypes")
            .apply { isAccessible = true }
            .get(toolWindowInstance) as MutableMap<String, String>

        selectedVersions[key] = "2.0.0"
        knownDependencies[key] = "1.0.0"
        knownTypes[key] = "dependency"

        val updates = toolWindowInstance.collectSelectedUpdates()

        assertEquals(1, updates.size)
        assertEquals("com.example", updates.single().groupId)
        assertEquals("example-core", updates.single().artifactId)
        assertEquals("1.0.0", updates.single().oldVersion)
        assertEquals("2.0.0", updates.single().newVersion)
    }

    fun testVersionAutoSelectionModeSetting() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val settings = MavenUpSettings.getInstance()

        // Mock data
        val key = "com.example:test-artifact"
        val versions = listOf("1.1.0", "1.0.0")
        val currentVersion = "1.0.0"

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
        
        // Use reflection to access internal maps for verification
        val availableVersionsField = toolWindowInstance.javaClass.getDeclaredField("availableVersions").apply { isAccessible = true }
        val selectedVersionsField = toolWindowInstance.javaClass.getDeclaredField("selectedVersions").apply { isAccessible = true }
        val knownDependenciesField = toolWindowInstance.javaClass.getDeclaredField("knownDependencies").apply { isAccessible = true }

        val availableVersions = availableVersionsField.get(toolWindowInstance) as MutableMap<String, List<String>>
        val selectedVersions = selectedVersionsField.get(toolWindowInstance) as MutableMap<String, String>
        val knownDependencies = knownDependenciesField.get(toolWindowInstance) as MutableMap<String, String>

        knownDependencies[key] = currentVersion
        
        // Simulate checkArtifactUpdate logic manually for testing the selection logic
        fun simulateCheck(v: String) {
            availableVersions[key] = versions
            if (versions.first() != v &&
                settings.state.versionAutoSelectionMode != VersionAutoSelectionMode.DISABLED
            ) {
                selectedVersions[key] = versions.first()
            } else if (settings.state.versionAutoSelectionMode == VersionAutoSelectionMode.DISABLED) {
                selectedVersions[key] = v
            }
        }

        simulateCheck(currentVersion)
        assertEquals("1.1.0", selectedVersions[key])

        // Test with VersionAutoSelectionMode.DISABLED
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
        selectedVersions.clear()
        simulateCheck(currentVersion)
        assertEquals("1.0.0", selectedVersions[key])
        
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
    }

    fun testUpdatesFilterIsDisabledUntilSuccessfulVersionScan() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)

        assertFalse(toolWindowInstance.isUpdatesFilterAvailable())
        assertFalse(toolWindowInstance.updatesFilterComboBox.isEnabled)

        val availableVersionsField = toolWindowInstance.javaClass
            .getDeclaredField("availableVersions").apply { isAccessible = true }
        val availableVersions =
            availableVersionsField.get(toolWindowInstance) as MutableMap<String, List<String>>

        // A scan that returned no versions must keep the filter disabled.
        availableVersions["com.example:empty"] = emptyList()
        toolWindowInstance.updateUpdatesFilterState()
        assertFalse(toolWindowInstance.isUpdatesFilterAvailable())
        assertFalse(toolWindowInstance.updatesFilterComboBox.isEnabled)

        // A successful scan with results enables the filter.
        availableVersions["com.example:lib"] = listOf("2.0.0", "1.0.0")
        toolWindowInstance.updateUpdatesFilterState()
        assertTrue(toolWindowInstance.isUpdatesFilterAvailable())
        assertTrue(toolWindowInstance.updatesFilterComboBox.isEnabled)
    }

    fun testUpdatesFilterSelectionResetsWhenScanResultsCleared() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)

        val availableVersionsField = toolWindowInstance.javaClass
            .getDeclaredField("availableVersions").apply { isAccessible = true }
        val availableVersions =
            availableVersionsField.get(toolWindowInstance) as MutableMap<String, List<String>>

        availableVersions["com.example:lib"] = listOf("2.0.0", "1.0.0")
        toolWindowInstance.updateUpdatesFilterState()
        toolWindowInstance.updatesFilterComboBox.selectedItem = TriStateFilter.YES

        availableVersions.clear()
        toolWindowInstance.updateUpdatesFilterState()

        assertFalse(toolWindowInstance.updatesFilterComboBox.isEnabled)
        assertEquals(TriStateFilter.ALL, toolWindowInstance.updatesFilterComboBox.selectedItem)
    }

    fun testTypeFilterOptionsAlwaysIncludeAllKnownTypeCategories() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)
        val knownTypesField = toolWindowInstance.javaClass
            .getDeclaredField("knownTypes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val knownTypes = knownTypesField.get(toolWindowInstance) as MutableMap<String, String>
        knownTypes.clear()

        val tableField = toolWindowInstance.javaClass.getDeclaredField("table").apply { isAccessible = true }
        val table = tableField.get(toolWindowInstance) as JBTable
        (table.model as DefaultTableModel).setRowCount(0)

        toolWindowInstance.updateTypeFilterOptions()

        val options = (0 until toolWindowInstance.typeFilterComboBox.itemCount)
            .map { toolWindowInstance.typeFilterComboBox.getItemAt(it) as String }
            .toSet()

        assertTrue(options.contains(MyMessageBundle.message("toolwindow.MyToolWindow.filter.type.all")))
        assertTrue(options.contains("dependency"))
        assertTrue(options.contains("plugin"))
        assertTrue(options.contains(PARENT_TYPE))
        assertTrue(options.contains(MANAGED_PLUGIN))
        assertTrue(options.contains(MANAGED_DEPENDENCY))
    }

    fun testVulnerabilitiesFilterIsDisabledUntilSuccessfulVulnerabilityScan() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)

        assertFalse(toolWindowInstance.isVulnerabilitiesFilterAvailable())
        assertFalse(toolWindowInstance.vulnerabilitiesFilterComboBox.isEnabled)

        val scanPerformedField = toolWindowInstance.javaClass
            .getDeclaredField("vulnerabilityScanPerformed").apply { isAccessible = true }

        scanPerformedField.setBoolean(toolWindowInstance, true)
        toolWindowInstance.updateVulnerabilitiesFilterState()
        assertTrue(toolWindowInstance.isVulnerabilitiesFilterAvailable())
        assertTrue(toolWindowInstance.vulnerabilitiesFilterComboBox.isEnabled)
    }

    fun testVulnerabilitiesFilterSelectionResetsWhenScanStateCleared() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)

        val scanPerformedField = toolWindowInstance.javaClass
            .getDeclaredField("vulnerabilityScanPerformed").apply { isAccessible = true }

        scanPerformedField.setBoolean(toolWindowInstance, true)
        toolWindowInstance.updateVulnerabilitiesFilterState()
        toolWindowInstance.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.VULNERABLE

        scanPerformedField.setBoolean(toolWindowInstance, false)
        toolWindowInstance.updateVulnerabilitiesFilterState()

        assertFalse(toolWindowInstance.vulnerabilitiesFilterComboBox.isEnabled)
        assertEquals(VulnerabilityFilter.ALL, toolWindowInstance.vulnerabilitiesFilterComboBox.selectedItem)
    }

    fun testChangesFilterIsDisabledUntilVersionSelectionDiffers() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)

        assertFalse(toolWindowInstance.isChangesFilterAvailable())
        assertFalse(toolWindowInstance.changesFilterComboBox.isEnabled)

        val knownDependenciesField = toolWindowInstance.javaClass
            .getDeclaredField("knownDependencies").apply { isAccessible = true }
        val selectedVersionsField = toolWindowInstance.javaClass
            .getDeclaredField("selectedVersions").apply { isAccessible = true }
        val knownDependencies =
            knownDependenciesField.get(toolWindowInstance) as MutableMap<String, String>
        val selectedVersions =
            selectedVersionsField.get(toolWindowInstance) as MutableMap<String, String>

        knownDependencies["com.example:lib"] = "1.0.0"

        // Selecting the current version again is not a pending change.
        selectedVersions["com.example:lib"] = "1.0.0"
        toolWindowInstance.updateChangesFilterState()
        assertFalse(toolWindowInstance.isChangesFilterAvailable())
        assertFalse(toolWindowInstance.changesFilterComboBox.isEnabled)

        // A differing selection enables the filter.
        selectedVersions["com.example:lib"] = "2.0.0"
        toolWindowInstance.updateChangesFilterState()
        assertTrue(toolWindowInstance.isChangesFilterAvailable())
        assertTrue(toolWindowInstance.changesFilterComboBox.isEnabled)
    }

    fun testChangesFilterSelectionResetsWhenNoPendingChangeRemains() {
        val toolWindowInstance = MavenUpWindowFactory().MyToolWindow(project)

        val knownDependenciesField = toolWindowInstance.javaClass
            .getDeclaredField("knownDependencies").apply { isAccessible = true }
        val selectedVersionsField = toolWindowInstance.javaClass
            .getDeclaredField("selectedVersions").apply { isAccessible = true }
        val knownDependencies =
            knownDependenciesField.get(toolWindowInstance) as MutableMap<String, String>
        val selectedVersions =
            selectedVersionsField.get(toolWindowInstance) as MutableMap<String, String>

        knownDependencies["com.example:lib"] = "1.0.0"
        selectedVersions["com.example:lib"] = "2.0.0"
        toolWindowInstance.updateChangesFilterState()
        toolWindowInstance.changesFilterComboBox.selectedItem = PendingChangesFilter.WILL_UPDATE

        selectedVersions.clear()
        toolWindowInstance.updateChangesFilterState()

        assertFalse(toolWindowInstance.changesFilterComboBox.isEnabled)
        assertEquals(PendingChangesFilter.ALL, toolWindowInstance.changesFilterComboBox.selectedItem)
    }

    fun testLatestMinorSelectionIgnoresOtherMajorLinesAndKeepsCurrentVersion() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val settings = MavenUpSettings.getInstance()
        val originalMode = settings.state.versionAutoSelectionMode
        try {
            settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST_MINOR

            val availableVersionsField = toolWindowInstance.javaClass.getDeclaredField("availableVersions")
                .apply { isAccessible = true }
            val selectedVersionsField = toolWindowInstance.javaClass.getDeclaredField("selectedVersions")
                .apply { isAccessible = true }
            val knownDependenciesField = toolWindowInstance.javaClass.getDeclaredField("knownDependencies")
                .apply { isAccessible = true }

            val availableVersions = availableVersionsField.get(toolWindowInstance) as MutableMap<String, List<String>>
            val selectedVersions = selectedVersionsField.get(toolWindowInstance) as MutableMap<String, String>
            val knownDependencies = knownDependenciesField.get(toolWindowInstance) as MutableMap<String, String>

            val key = "com.example:jumped-artifact"
            knownDependencies[key] = "24.0"
            availableVersions[key] = listOf("2023-1234", "2022-1234", "2025-1234")

            toolWindowInstance.applySelectLatestVersionSetting()

            assertNull("Versionen aus einer anderen Major-Linie dürfen nicht als LATEST_MINOR-Auswahl dienen", selectedVersions[key])

            availableVersions[key] = listOf("24.9", "24.3", "25.0")
            toolWindowInstance.applySelectLatestVersionSetting()
            assertEquals("24.9", selectedVersions[key])
        } finally {
            settings.state.versionAutoSelectionMode = originalMode
        }
    }

    fun testJumpOnSingleClickSetting() {
        val settings = MavenUpSettings.getInstance()
        
        // Test default value
        assertFalse("jumpOnSingleClick sollte standardmäßig false sein", settings.state.jumpOnSingleClick)
        
        // Test update
        settings.state.jumpOnSingleClick = true
        assertTrue("jumpOnSingleClick sollte nun true sein", settings.state.jumpOnSingleClick)
        
        // Reset
        settings.state.jumpOnSingleClick = false
    }

    fun testUnstableVersionFilterSettingsDefaults() {
        val defaults = MavenUpSettings.State()
        assertFalse(defaults.hideUnstableVersions)
        assertTrue(defaults.hiddenVersionQualifiers.isNotBlank())
        assertTrue(defaults.hiddenVersionQualifiers.contains("rc"))
    }

    fun testBuildMavenRepositoryUrl() {
        assertEquals(
            "https://mvnrepository.com/artifact/org.springframework/spring-core/5.3.10",
            buildMavenRepositoryUrl("org.springframework", "spring-core", "5.3.10", MavenRepositoryBrowser.MVN_REPOSITORY)
        )
        assertEquals(
            "https://central.sonatype.com/artifact/org.springframework/spring-core/5.3.10",
            buildMavenRepositoryUrl("org.springframework", "spring-core", "5.3.10", MavenRepositoryBrowser.SONATYPE_CENTRAL)
        )
        // default browser is MVN_REPOSITORY
        assertEquals(
            "https://mvnrepository.com/artifact/com.example/my-lib/1.0.0",
            buildMavenRepositoryUrl("com.example", "my-lib", "1.0.0")
        )
    }

    private fun findTable(container: Container): JBTable? {
        container.components.forEach { component ->
            if (component is JBTable) return component
            if (component is Container) findTable(component)?.let { return it }
        }
        return null
    }

    private fun findComboBox(container: Container): javax.swing.JComboBox<*>? {
        container.components.forEach { component ->
            if (component is javax.swing.JComboBox<*>) return component
            if (component is Container) findComboBox(component)?.let { return it }
        }
        return null
    }

    fun testNewVersionCellDefaultsToCurrentVersionWhenNoSelectionStored() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull("Die Haupttabelle sollte vorhanden sein", table)

        val model = table!!.model as DefaultTableModel
        // Versionen wie vom Fetch geliefert: absteigend nach ComparableVersion sortiert,
        // wodurch datumsbasierte Versionen (Major 2023/2025) vor der aktuellen 24.0 stehen.
        val versions = listOf("2025-1234", "2023-1234", "24.0")
        model.addRow(
            arrayOf("com.example", "jump-lib", "", "dependency", null, "24.0", versions)
        )

        val renderer = table.columnModel.getColumn(6).cellRenderer
        val component = renderer.getTableCellRendererComponent(table, versions, false, false, 0, 6) as Container
        val combo = findComboBox(component)

        assertNotNull("Die New-Version-Zelle sollte eine ComboBox rendern", combo)
        assertEquals(
            "Ohne gespeicherte Auswahl muss die aktuelle Version angezeigt werden, nicht die numerisch höchste",
            "24.0",
            combo!!.selectedItem
        )
    }

    fun testMainTableUsesSingleSelection() {
        val content = MavenUpWindowFactory().MyToolWindow(project).getContent()

        val table = findTable(content)
        assertNotNull("Die Haupttabelle sollte im Tool Window vorhanden sein", table)
        assertEquals(
            "Die Haupttabelle sollte nur Einzelselektion erlauben",
            javax.swing.ListSelectionModel.SINGLE_SELECTION,
            table!!.selectionModel.selectionMode
        )
        assertFalse(
            "Die Haupttabelle sollte kein Umordnen der Spalten erlauben",
            table.tableHeader.reorderingAllowed
        )
    }

    fun testCancelActiveCellEditingStopsEditingBeforeTableRebuild() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull("Die Haupttabelle sollte vorhanden sein", table)

        val model = table!!.model as DefaultTableModel
        model.addRow(
            arrayOf("com.example", "my-lib", "", "dependency", null, "1.0.0", listOf("1.1.0", "1.0.0"))
        )

        // Bearbeitung der Spalte "New Version" starten
        assertTrue(
            "Die New-Version-Zelle sollte in den Bearbeitungsmodus wechseln",
            table.editCellAt(0, 6)
        )
        assertTrue("Die Tabelle sollte sich im Bearbeitungsmodus befinden", table.isEditing)

        // Abbruch der Bearbeitung; danach darf das Leeren der Zeilen keine Exception auslösen
        assertTrue(
            "Eine laufende Bearbeitung sollte abgebrochen werden",
            toolWindow.cancelActiveCellEditing()
        )
        assertFalse("Nach dem Abbruch sollte keine Bearbeitung mehr laufen", table.isEditing)

        model.setRowCount(0)
        table.doLayout()

        // Ohne aktive Bearbeitung meldet der Aufruf, dass nichts abzubrechen war
        assertFalse(
            "Ohne laufende Bearbeitung sollte kein Abbruch gemeldet werden",
            toolWindow.cancelActiveCellEditing()
        )
    }

    fun testConfirmChangesDialogTableIsNotEditable() {
        val updates = listOf(
            DependencyUpdate("com.example", "demo-lib", "dependency", "1.0.0", "1.1.0")
        )
        val dialog = UpdateConfirmationDialog(project, updates)
        val table = dialog.buildTable()
        for (column in 0 until table.columnCount) {
            assertFalse(
                "Zelle in Spalte $column sollte nicht editierbar sein",
                table.isCellEditable(0, column)
            )
        }
        assertEquals(
            "Die Confirm-Changes-Tabelle sollte nur Einzelselektion erlauben",
            javax.swing.ListSelectionModel.SINGLE_SELECTION,
            table.selectionModel.selectionMode
        )
        assertFalse(
            "Die Confirm-Changes-Tabelle sollte kein Umordnen der Spalten erlauben",
            table.tableHeader.reorderingAllowed
        )
    }

    fun testConfirmChangesDialogFirstThreeColumnsAreSortable() {
        val updates = listOf(
            DependencyUpdate("com.example", "demo-lib", "dependency", "1.0.0", "1.1.0")
        )
        val table = UpdateConfirmationDialog(project, updates).buildTable()
        val sorter = table.rowSorter as TableRowSorter<*>
        for (column in 0 until UpdateConfirmationDialog.CONFIRM_CURRENT_VERSION_COLUMN) {
            assertTrue("Spalte $column sollte sortierbar sein", sorter.isSortable(column))
        }
        for (column in UpdateConfirmationDialog.CONFIRM_CURRENT_VERSION_COLUMN until table.columnCount) {
            assertFalse("Versionsspalte $column sollte nicht sortierbar sein", sorter.isSortable(column))
        }
    }

    fun testConfirmChangesDialogSortsTextColumnsCaseInsensitively() {
        val updates = listOf(
            DependencyUpdate("org.zulu", "zeta-lib", "dependency", "1.0.0", "1.1.0"),
            DependencyUpdate("com.Alpha", "alpha-lib", "dependency", "2.0.0", "2.1.0")
        )
        val table = UpdateConfirmationDialog(project, updates).buildTable()
        table.rowSorter.toggleSortOrder(0)
        assertEquals(
            "Nach aufsteigender Sortierung sollte com.Alpha zuerst stehen",
            "com.Alpha",
            table.getValueAt(0, 0)
        )
        table.rowSorter.toggleSortOrder(0)
        assertEquals(
            "Nach absteigender Sortierung sollte org.zulu zuerst stehen",
            "org.zulu",
            table.getValueAt(0, 0)
        )
    }

    fun testConfirmChangesDialogSortCycleReturnsToOriginalOrder() {
        val updates = listOf(
            DependencyUpdate("org.zulu", "zeta-lib", "dependency", "1.0.0", "1.1.0"),
            DependencyUpdate("com.alpha", "alpha-lib", "dependency", "2.0.0", "2.1.0")
        )
        val table = UpdateConfirmationDialog(project, updates).buildTable()
        repeat(3) { table.rowSorter.toggleSortOrder(0) }
        assertTrue(
            "Der dritte Klick sollte die Sortierung aufheben",
            table.rowSorter.sortKeys.isEmpty()
        )
        assertEquals(
            "Ohne Sortierung sollte die ursprüngliche Reihenfolge gelten",
            "org.zulu",
            table.getValueAt(0, 0)
        )
    }

    fun testConfirmChangesDialogIgnoresToggleOnNonSortableColumn() {
        val updates = listOf(
            DependencyUpdate("com.example", "demo-lib", "dependency", "1.0.0", "1.1.0")
        )
        val table = UpdateConfirmationDialog(project, updates).buildTable()
        table.rowSorter.toggleSortOrder(UpdateConfirmationDialog.CONFIRM_CURRENT_VERSION_COLUMN)
        assertTrue(
            "Eine nicht sortierbare Spalte sollte keine Sortierung auslösen",
            table.rowSorter.sortKeys.isEmpty()
        )
    }

    fun testConfirmChangesDialogMarksTransitiveUpdates() {
        val transitiveUpdate = DependencyUpdate(
            "com.example",
            "trans-lib",
            "managed dependency",
            "1.0.0",
            "1.1.0",
            transitive = true
        )
        val directUpdate = DependencyUpdate("com.example", "demo-lib", "dependency", "1.0.0", "1.1.0")
        val dialog = UpdateConfirmationDialog(project, listOf(transitiveUpdate, directUpdate))

        assertEquals(
            "Transitive Updates sollten den Zieltyp mit Herkunft anzeigen",
            "transitive -> managed dependency",
            dialog.typeLabel(transitiveUpdate)
        )
        assertEquals(
            "Direkte Updates sollten unveraendert ihren Typ anzeigen",
            "dependency",
            dialog.typeLabel(directUpdate)
        )

        val table = dialog.buildTable()
        assertEquals(
            "Die Typ-Spalte sollte den transitiven Hinweis enthalten",
            "transitive -> managed dependency",
            table.getValueAt(0, 2)
        )
        assertEquals("dependency", table.getValueAt(1, 2))
    }

    fun testConfirmChangesDialogSyncCheckboxReflectsSetting() {
        val settings = MavenUpSettings.getInstance()
        val original = settings.state.syncMavenAfterUpdate
        try {
            val updates = listOf(
                DependencyUpdate("com.example", "demo-lib", "dependency", "1.0.0", "1.1.0")
            )

            settings.state.syncMavenAfterUpdate = false
            val disabledDialog = UpdateConfirmationDialog(project, updates)
            assertFalse(
                "Die Sync-Checkbox sollte den gespeicherten Wert false übernehmen",
                disabledDialog.isSyncMavenSelected()
            )

            settings.state.syncMavenAfterUpdate = true
            val enabledDialog = UpdateConfirmationDialog(project, updates)
            assertTrue(
                "Die Sync-Checkbox sollte den gespeicherten Wert true übernehmen",
                enabledDialog.isSyncMavenSelected()
            )
        } finally {
            settings.state.syncMavenAfterUpdate = original
        }
    }

    fun testOpenInRepositoryActionInitiallyDisabled() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertFalse(
            "Open-in-Repository-Aktion sollte ohne Selektion deaktiviert sein",
            toolWindow.isOpenInRepositoryEnabled()
        )
    }

    fun testOpenInRepositoryActionEnabledOnRowSelection() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull(table)

        // Tabelle ist leer – Aktion inaktiv
        assertFalse(
            "Open-in-Repository-Aktion sollte bei leerer Tabelle deaktiviert sein",
            toolWindow.isOpenInRepositoryEnabled()
        )

        // Eine Zeile hinzufügen und selektieren
        (table!!.model as? DefaultTableModel)?.addRow(
            arrayOf("com.example", "my-lib", "", "dependency", null, "1.0.0", emptyList<String>())
        )
        table.setRowSelectionInterval(0, 0)

        assertTrue(
            "Open-in-Repository-Aktion sollte bei selektierter Zeile aktiviert sein",
            toolWindow.isOpenInRepositoryEnabled()
        )

        // Selektion aufheben
        table.clearSelection()
        assertFalse(
            "Open-in-Repository-Aktion sollte ohne Selektion wieder deaktiviert sein",
            toolWindow.isOpenInRepositoryEnabled()
        )
    }

    @Suppress("OverrideOnly")
    fun testNavigateToPomActionEnabledOnlyForSelectedMainTableRows() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull(table)

        val pomAction = toolWindow.topToolbarActions()
            .first { it.templatePresentation.icon == AllIcons.General.Locate }
        val pomEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(pomAction)
        pomAction.update(pomEvent)
        assertEquals(
            "Die Toolbar-Aktion sollte die kurze Bezeichnung 'Locate' anzeigen",
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom.short"),
            pomEvent.presentation.text
        )
        assertSame(
            "Die pom.xml-Aktion sollte das Locate-Icon aus dem Hierarchie-Dialog verwenden",
            AllIcons.General.Locate,
            pomAction.templatePresentation.icon
        )

        assertFalse(
            "Navigate-to-pom-Aktion sollte ohne Selektion deaktiviert sein",
            toolWindow.isNavigateToPomEnabled()
        )

        (table!!.model as? DefaultTableModel)?.addRow(
            arrayOf("com.example", "my-lib", "", "dependency", null, "1.0.0", emptyList<String>())
        )
        table.setRowSelectionInterval(0, 0)
        assertTrue(
            "Navigate-to-pom-Aktion sollte bei selektierter Haupttabellenzeile aktiviert sein",
            toolWindow.isNavigateToPomEnabled()
        )

        val showingTransitiveView = toolWindow.javaClass.getDeclaredField("showingTransitiveView")
            .apply { isAccessible = true }
        showingTransitiveView.setBoolean(toolWindow, true)
        assertFalse(
            "Navigate-to-pom-Aktion sollte in der transitiven CVE-Ansicht deaktiviert sein",
            toolWindow.isNavigateToPomEnabled()
        )
        showingTransitiveView.setBoolean(toolWindow, false)

        table.clearSelection()
        assertFalse(
            "Navigate-to-pom-Aktion sollte ohne Auswahl wieder deaktiviert sein",
            toolWindow.isNavigateToPomEnabled()
        )
    }

    fun testOpenInRepositoryActionLabelReflectsConfiguredBrowser() {
        val settings = MavenUpSettings.getInstance()
        settings.state.repositoryBrowser = MavenRepositoryBrowser.SONATYPE_CENTRAL

        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        assertEquals(
            "Aktionstext sollte den konfigurierten Browser-Namen enthalten",
            "Open on Sonatype Central",
            toolWindow.currentOpenInRepositoryText()
        )

        settings.state.repositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY
    }

    @Suppress("OverrideOnly")
    fun testDependencyHierarchyToolbarActionPropertiesAndPosition() {
        val settings = MavenUpSettings.getInstance()
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val allActions = toolWindow.topToolbarActions()
        val hierarchyIndex = allActions.indexOfFirst {
            it.templatePresentation.text == "Show Dependency Hierarchy"
        }
        val detailsIndex = allActions.indexOfFirst {
            it.templatePresentation.text == "Vulnerability Details..."
        }

        assertTrue("Hierarchy-Aktion sollte in der Toolbar vorhanden sein", hierarchyIndex > 0)
        assertTrue("Details-Aktion sollte in der Toolbar vorhanden sein", detailsIndex > 0)
        assertEquals(
            "Details-Aktion muss direkt nach Hierarchy stehen",
            hierarchyIndex + 1,
            detailsIndex
        )

        val navigatePomIndex = hierarchyIndex - 1
        val navigatePomAction = allActions[navigatePomIndex]
        val navigatePomEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(navigatePomAction)
        navigatePomAction.update(navigatePomEvent)
        assertEquals(
            "Die Aktion direkt vor Hierarchy muss die pom.xml-Navigation sein",
            MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.navigateToPom.short"),
            navigatePomEvent.presentation.text
        )
        assertSame(
            "Die pom.xml-Navigation sollte das Locate-Icon verwenden",
            AllIcons.General.Locate,
            navigatePomAction.templatePresentation.icon
        )

        val openIndex = navigatePomIndex - 1
        val openAction = allActions[openIndex]
        val openEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(openAction)
        openAction.update(openEvent)
        assertEquals(
            "Die Aktion direkt vor der pom.xml-Navigation muss die Open-In-Repository-Aktion sein",
            "Open",
            openEvent.presentation.text
        )
        assertSame(
            "Die Open-Aktion sollte das Web-Icon verwenden",
            AllIcons.General.Web,
            openEvent.presentation.icon
        )

        val hierarchyAction = allActions[hierarchyIndex]
        assertSame(
            "Hierarchy-Aktion sollte AllIcons.Actions.ShowAsTree als Symbol verwenden",
            AllIcons.Actions.ShowAsTree,
            hierarchyAction.templatePresentation.icon
        )

        val expectedTooltip = MyMessageBundle.message("toolwindow.MyToolWindow.dependencyHierarchy.tooltip")

        settings.state.toolbarShowText = false
        val iconEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(hierarchyAction)
        hierarchyAction.update(iconEvent)
        assertEquals(
            "Im Icon-Modus muss der lange Text in der Beschreibung stehen",
            expectedTooltip,
            iconEvent.presentation.description
        )

        settings.state.toolbarShowText = true
        val textEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(hierarchyAction)
        hierarchyAction.update(textEvent)
        assertEquals(
            "Bei aktiven Textbeschriftungen zeigt der Button die Kurzform 'Hierarchy'",
            "Hierarchy",
            textEvent.presentation.text
        )
        assertEquals(
            "Der Tooltip bleibt in der Beschreibung erhalten",
            expectedTooltip,
            textEvent.presentation.description
        )

        settings.state.toolbarShowText = false
    }

    fun testDependencyHierarchyActionEnabledOnlyForManagedEntriesInMainTable() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull(table)

        val model = table!!.model as DefaultTableModel

        // Ohne Zeilen
        assertFalse(
            "Abhängigkeitshierarchie sollte ohne Selektion deaktiviert sein",
            toolWindow.isDependencyHierarchyEnabled()
        )

        // Direkte Dependency (nicht managed)
        model.addRow(arrayOf("com.example", "direct-lib", "", "dependency", null, "1.0.0", emptyList<String>()))
        table.setRowSelectionInterval(0, 0)
        assertFalse(
            "Abhängigkeitshierarchie sollte für normale direkte Abhängigkeiten deaktiviert sein",
            toolWindow.isDependencyHierarchyEnabled()
        )

        // Direktes Plugin (nicht managed)
        model.addRow(arrayOf("com.example", "direct-plugin", "", "plugin", null, "1.0.0", emptyList<String>()))
        table.setRowSelectionInterval(1, 1)
        assertFalse(
            "Abhängigkeitshierarchie sollte für normale Plugins deaktiviert sein",
            toolWindow.isDependencyHierarchyEnabled()
        )

        // Managed Dependency
        model.addRow(arrayOf("com.example", "managed-lib", "", "managed dependency", null, "1.0.0", emptyList<String>()))
        table.setRowSelectionInterval(2, 2)
        assertTrue(
            "Abhängigkeitshierarchie sollte für verwaltete Abhängigkeiten aktiviert sein",
            toolWindow.isDependencyHierarchyEnabled()
        )

        // Managed Plugin
        model.addRow(arrayOf("com.example", "managed-plugin", "", "managed plugin", null, "1.0.0", emptyList<String>()))
        table.setRowSelectionInterval(3, 3)
        assertTrue(
            "Abhängigkeitshierarchie sollte für verwaltete Plugins aktiviert sein",
            toolWindow.isDependencyHierarchyEnabled()
        )

        // Deaktiviert während isUpdating
        val updatingField = toolWindow.javaClass.getDeclaredField("isUpdating").apply { isAccessible = true }
        updatingField.setBoolean(toolWindow, true)
        assertFalse(
            "Abhängigkeitshierarchie sollte während laufender Aktualisierung deaktiviert sein",
            toolWindow.isDependencyHierarchyEnabled()
        )
        updatingField.setBoolean(toolWindow, false)

        // Selektion aufheben
        table.clearSelection()
        assertFalse(
            "Abhängigkeitshierarchie sollte ohne Selektion wieder deaktiviert sein",
            toolWindow.isDependencyHierarchyEnabled()
        )
    }

    fun testActionToolbarIsPresentAtTop() {
        val content = MavenUpWindowFactory().MyToolWindow(project).getContent()

        val toolbarComponent = (content.layout as? java.awt.BorderLayout)
            ?.getLayoutComponent(java.awt.BorderLayout.NORTH)
        assertNotNull("Die obere Aktionsleiste sollte im Tool Window vorhanden sein", toolbarComponent)
    }

    fun testBulkVersionActionsAreGroupedInPopupSubmenu() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val group = toolWindow.topToolbarActions()
            .filterIsInstance<DefaultActionGroup>()
            .firstOrNull { it.isPopup }
        assertNotNull("Die \"Select Highest\"-Aktionen sollten in einem Aufklappmenü gebündelt sein", group)
        assertEquals(
            "Das Untermenü sollte die beiden \"Select Highest\"-Aktionen und die empfohlene Version enthalten",
            3,
            group!!.childrenCount
        )
        assertSame(
            "Das \"Select Highest\"-Untermenü sollte denselben Aufwärtspfeil wie die New-Version-Spalte verwenden",
            VersionUpdateArrowIcon,
            group.templatePresentation.icon
        )
    }

    @Suppress("OverrideOnly")
    fun testResetTooltipUsesWrappingDescriptionInIconMode() {
        val settings = MavenUpSettings.getInstance()
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val resetAction = toolWindow.topToolbarActions()
            .filter { it !is com.intellij.openapi.actionSystem.ActionGroup }
            .first { it.templatePresentation.text == "Reset All to Current Versions" }
        val longTooltip = MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.tooltip")

        settings.state.toolbarShowText = false
        val iconEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(resetAction)
        resetAction.update(iconEvent)
        assertEquals(
            "Im Icon-Modus muss der lange Text in der (umbrechenden) Beschreibung stehen",
            longTooltip,
            iconEvent.presentation.description
        )
        assertFalse(
            "Im Icon-Modus darf der lange Text nicht als einzeiliger Titel (text) gesetzt werden",
            iconEvent.presentation.text == longTooltip
        )

        settings.state.toolbarShowText = true
        val textEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(resetAction)
        resetAction.update(textEvent)
        assertEquals(
            "Bei aktiven Textbeschriftungen zeigt der Button die Kurzform",
            "Reset",
            textEvent.presentation.text
        )
        assertEquals(
            "Der lange Text bleibt in der umbrechenden Beschreibung",
            longTooltip,
            textEvent.presentation.description
        )

        settings.state.toolbarShowText = false
    }

    fun testResetVersionsActionIsTopLevelToolbarAction() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val hasGroup = toolWindow.topToolbarActions()
            .filterIsInstance<DefaultActionGroup>()
            .any { it.isPopup }
        assertTrue("Das \"Select Highest\"-Untermenü sollte vorhanden sein", hasGroup)

        val topLevelActionTexts = toolWindow.topToolbarActions()
            .filter { it !is com.intellij.openapi.actionSystem.ActionGroup }
            .map { it.templatePresentation.text }
        assertTrue(
            "Reset All to Current Versions sollte als eigene Toolbar-Aktion vorliegen, nicht im Untermenü",
            topLevelActionTexts.contains("Reset All to Current Versions")
        )
    }

    fun testShortToolbarLabelsResolveToShortenedText() {
        assertEquals("Refresh", MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button.short"))
        assertEquals("Scan", MyMessageBundle.message("toolwindow.MyToolWindow.checkVulnerabilities.button.short"))
        assertEquals("Update", MyMessageBundle.message("toolwindow.MyToolWindow.update.button.short"))
        assertEquals("Open", MyMessageBundle.message("toolwindow.MyToolWindow.openInRepository.button.short"))
        assertEquals("Hierarchy", MyMessageBundle.message("toolwindow.MyToolWindow.dependencyHierarchy.button.short"))
        assertEquals("Details", MyMessageBundle.message("toolwindow.MyToolWindow.vulnerabilityDetails.button.short"))
        assertEquals("Reset", MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.button.short"))
        assertEquals("Transitive CVEs", MyMessageBundle.message("toolwindow.MyToolWindow.tab.transitiveView"))
    }

    fun testResetActionLabelAndTooltipConveyFilterAwareScope() {
        assertEquals(
            "Reset All to Current Versions",
            MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.button")
        )
        val tooltip = MyMessageBundle.message("toolwindow.MyToolWindow.resetVersions.tooltip")
        assertTrue(
            "Der Reset-Tooltip sollte den filterlosen Fall (alle Abhängigkeiten) benennen",
            tooltip.contains("all dependencies", ignoreCase = true)
        )
        assertTrue(
            "Der Reset-Tooltip sollte den Fall eines aktiven Filters benennen",
            tooltip.contains("filter", ignoreCase = true)
        )
    }

    fun testHighestVersionGroupHasLabelAndTooltip() {
        assertEquals("Select Highest Version", MyMessageBundle.message("toolwindow.MyToolWindow.versionActions.group.button"))
        assertEquals("Highest", MyMessageBundle.message("toolwindow.MyToolWindow.versionActions.group.button.short"))
        val tooltip = MyMessageBundle.message("toolwindow.MyToolWindow.versionActions.group.tooltip")
        assertTrue(
            "Der Tooltip sollte den Fall eines aktiven Filters benennen",
            tooltip.contains("filter", ignoreCase = true)
        )
        assertTrue(
            "Der Tooltip sollte die Wahl zwischen allen und sichtbaren Dependencies benennen",
            tooltip.contains("all dependencies", ignoreCase = true) &&
                tooltip.contains("visible", ignoreCase = true)
        )
    }

    fun testToolbarTextEnabledReflectsSetting() {
        val settings = MavenUpSettings.getInstance()
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        settings.state.toolbarShowText = false
        assertFalse(
            "Text-Modus sollte deaktiviert gemeldet werden, wenn die Einstellung aus ist",
            toolWindow.isToolbarTextEnabled()
        )

        settings.state.toolbarShowText = true
        assertTrue(
            "Text-Modus sollte aktiviert gemeldet werden, wenn die Einstellung an ist",
            toolWindow.isToolbarTextEnabled()
        )

        settings.state.toolbarShowText = false
    }

    @Suppress("UnstableApiUsage", "OverrideOnly")
    fun testToolbarTooltipsAreIdenticalRegardlessOfTextLabels() {
        val settings = MavenUpSettings.getInstance()
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val actions = toolWindow.topToolbarActions()
            .filter { it !is com.intellij.openapi.actionSystem.Separator }

        // Der effektiv gerenderte Tooltip wird über CUSTOM_HELP_TOOLTIP vollständig vorgegeben und von
        // ActionButton (Icon-Modus) wie ActionButtonWithText (Text-Modus) unverändert übernommen.
        fun tooltipTexts(showText: Boolean): List<String?> {
            settings.state.toolbarShowText = showText
            return actions.map { action ->
                val event = com.intellij.testFramework.TestActionEvent.createTestEvent(action)
                action.update(event)
                val custom = event.presentation.getClientProperty(
                    com.intellij.openapi.actionSystem.impl.ActionButton.CUSTOM_HELP_TOOLTIP
                )
                custom?.description
            }
        }

        val withoutText = tooltipTexts(false)
        val withText = tooltipTexts(true)

        assertEquals(
            "Die Tooltips müssen unabhängig von den Textbeschriftungen identisch sein",
            withText,
            withoutText
        )
        assertTrue(
            "Jede Toolbar-Aktion muss einen nicht-leeren Tooltip besitzen",
            withoutText.all { !it.isNullOrBlank() }
        )

        settings.state.toolbarShowText = false
    }

    @Suppress("UnstableApiUsage", "OverrideOnly")
    fun testCheckVulnerabilitiesToolbarTooltipReflectsSettings() {
        val settings = MavenUpSettings.getInstance()
        val originalTransitive = settings.state.checkTransitiveDependencies
        val originalOssIndex = settings.state.ossIndexEnabled

        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val scanAction = toolWindow.topToolbarActions()
            .first { it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.checkVulnerabilities.button") }

        fun currentTooltip(): String? {
            val event = com.intellij.testFramework.TestActionEvent.createTestEvent(scanAction)
            scanAction.update(event)
            val custom = event.presentation.getClientProperty(
                com.intellij.openapi.actionSystem.impl.ActionButton.CUSTOM_HELP_TOOLTIP
            )
            return custom?.description
        }

        try {
            settings.state.checkTransitiveDependencies = true
            settings.state.ossIndexEnabled = true
            assertEquals(
                MyMessageBundle.message(CHECK_VULNERABILITIES_TOOLTIP_TRANSITIVE_ALL_SOURCES_KEY),
                currentTooltip()
            )

            settings.state.checkTransitiveDependencies = true
            settings.state.ossIndexEnabled = false
            assertEquals(
                MyMessageBundle.message(CHECK_VULNERABILITIES_TOOLTIP_TRANSITIVE_OSV_ONLY_KEY),
                currentTooltip()
            )

            settings.state.checkTransitiveDependencies = false
            settings.state.ossIndexEnabled = true
            assertEquals(
                MyMessageBundle.message(CHECK_VULNERABILITIES_TOOLTIP_DIRECT_ALL_SOURCES_KEY),
                currentTooltip()
            )

            settings.state.checkTransitiveDependencies = false
            settings.state.ossIndexEnabled = false
            assertEquals(
                MyMessageBundle.message(CHECK_VULNERABILITIES_TOOLTIP_DIRECT_OSV_ONLY_KEY),
                currentTooltip()
            )
        } finally {
            settings.state.checkTransitiveDependencies = originalTransitive
            settings.state.ossIndexEnabled = originalOssIndex
        }
    }

    fun testVulnerabilityDetailsActionReflectsSelection() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)
        assertNotNull(table)

        assertFalse(
            "Vulnerability-Details-Aktion sollte ohne Selektion deaktiviert sein",
            toolWindow.isVulnerabilityDetailsEnabled()
        )

        val advisory = VulnerabilityAdvisory(
            id = "CVE-1",
            severity = VulnerabilitySeverity.MEDIUM,
            sources = setOf("OSV"),
            references = setOf("https://example.test")
        )
        val cell = buildVulnerabilityCell(
            "com.example:my-lib:1.0.0",
            mapOf("com.example:my-lib:1.0.0" to listOf(advisory)),
            emptySet()
        )
        (table!!.model as? DefaultTableModel)?.addRow(
            arrayOf("com.example", "my-lib", "", "dependency", cell, "1.0.0", emptyList<String>())
        )
        table.setRowSelectionInterval(0, 0)

        assertTrue(
            "Vulnerability-Details-Aktion sollte bei Zeile mit Befunden aktiviert sein",
            toolWindow.isVulnerabilityDetailsEnabled()
        )
    }

    /**
     * Baut ein Tool Window mit manuell erzeugten Tabs auf, verbindet es über `bindTabs` mit einer
     * [MavenUpWindowFactory.MyToolWindow] und führt [block] darauf aus.
     */
    @Suppress("OverrideOnly", "DEPRECATION")
    private fun withBoundToolWindow(
        block: (MavenUpWindowFactory.MyToolWindow, ContentManager, Content, Content) -> Unit
    ) {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = manager.registerToolWindow(
            RegisterToolWindowTask(
                id = "TestWindow",
                anchor = ToolWindowAnchor.BOTTOM,
                canCloseContent = true
            )
        )
        try {
            val window = MavenUpWindowFactory().MyToolWindow(project)
            val contentFactory = ContentFactory.getInstance()
            val dependenciesTab = contentFactory.createContent(window.getContent(), "Dependencies", false)
            val transitiveTab = contentFactory.createContent(window.getTransitiveContent(), "Transitive CVEs", false)
            toolWindow.contentManager.addContent(dependenciesTab)
            toolWindow.contentManager.addContent(transitiveTab)
            window.bindTabs(toolWindow.contentManager, dependenciesTab, transitiveTab)
            block(window, toolWindow.contentManager, dependenciesTab, transitiveTab)
        } finally {
            manager.unregisterToolWindow("TestWindow")
        }
    }

    /**
     * Trägt eine verwundbare transitive Koordinate in den internen Zustand des Tool Windows ein.
     */
    private fun addTransitiveFinding(
        toolWindow: MavenUpWindowFactory.MyToolWindow,
        coordinate: String = "com.example:transitive:2.0.0"
    ) {
        val coords = toolWindow.javaClass.getDeclaredField("transitiveCoordinates")
            .apply { isAccessible = true }.get(toolWindow) as MutableSet<String>
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>
        coords.add(coordinate)
        advisories[coordinate] = listOf(
            VulnerabilityAdvisory(id = "CVE-1", severity = VulnerabilitySeverity.HIGH, sources = setOf("OSV"))
        )
    }

    fun testToolWindowRegistersDependenciesAndTransitiveTabs() {
        withToolWindowContents { contentManager ->
            assertEquals("Das Tool Window muss zwei Tabs anbieten", 2, contentManager.contentCount)
            assertEquals("Dependencies", contentManager.getContent(0)?.displayName)
            assertEquals("Transitive CVEs", contentManager.getContent(1)?.displayName)
            assertFalse(
                "Der Dependencies-Tab darf nicht schließbar sein",
                contentManager.getContent(0)!!.isCloseable
            )
            assertFalse(
                "Der Transitive-CVEs-Tab darf nicht schließbar sein",
                contentManager.getContent(1)!!.isCloseable
            )
        }
    }

    fun testTransitiveTabTitleShowsFindingCountAndSelectionIsTracked() {
        withBoundToolWindow { toolWindow, contentManager, dependenciesTab, transitiveTab ->
            val showingField = toolWindow.javaClass.getDeclaredField("showingTransitiveView")
                .apply { isAccessible = true }

            assertEquals(
                "Ohne Funde trägt der Tab den Titel ohne Zähler",
                "Transitive CVEs",
                transitiveTab.displayName
            )

            addTransitiveFinding(toolWindow)
            toolWindow.updateTransitiveVulnerabilitiesView()
            assertEquals("Transitive CVEs (1)", transitiveTab.displayName)

            toolWindow.setTransitiveViewVisible(true)
            assertSame(transitiveTab, contentManager.selectedContent)
            assertTrue("Der Tab-Wechsel muss im Zustand ankommen", showingField.getBoolean(toolWindow))

            toolWindow.setTransitiveViewVisible(false)
            assertSame(dependenciesTab, contentManager.selectedContent)
            assertFalse("Der Rückwechsel muss im Zustand ankommen", showingField.getBoolean(toolWindow))
        }
    }

    fun testTransitiveTabStaysSelectableWithoutFindings() {
        withBoundToolWindow { toolWindow, contentManager, _, transitiveTab ->
            toolWindow.setTransitiveViewVisible(true)

            assertSame(
                "Der Tab bleibt gemäß UI-Guidelines auch ohne Funde auswählbar",
                transitiveTab,
                contentManager.selectedContent
            )
        }
    }

    fun testTransitiveTabKeepsSelectionWhenFindingsAreCleared() {
        withBoundToolWindow { toolWindow, contentManager, _, transitiveTab ->
            val coords = toolWindow.javaClass.getDeclaredField("transitiveCoordinates")
                .apply { isAccessible = true }.get(toolWindow) as MutableSet<String>
            val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
                .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>

            addTransitiveFinding(toolWindow)
            toolWindow.updateTransitiveVulnerabilitiesView()
            toolWindow.setTransitiveViewVisible(true)

            coords.clear()
            advisories.clear()
            toolWindow.updateTransitiveVulnerabilitiesView()

            assertSame(
                "Der Tab wird nicht mehr automatisch gewechselt",
                transitiveTab,
                contentManager.selectedContent
            )
            assertEquals("Transitive CVEs", transitiveTab.displayName)
        }
    }

    fun testTransitiveViewShowsEmptyStateInsteadOfDisabledTab() {
        val view = TransitiveVulnerabilitiesView(project) {}

        assertTrue(
            "Ohne Funde muss der Empty State auf den Scan hinweisen",
            view.table.emptyText.text.contains("Scan for Vulnerabilities")
        )

        val coordinate = "com.example:transitive:2.0.0"
        view.update(
            mapOf(
                coordinate to listOf(
                    VulnerabilityAdvisory(id = "CVE-1", severity = VulnerabilitySeverity.HIGH, sources = setOf("OSV"))
                )
            ),
            setOf(coordinate)
        )

        assertEquals(
            "Mit Funden erklärt der Empty State nur noch einen leeren Filter",
            "No transitive dependency matches the current filter.",
            view.table.emptyText.text
        )
    }

    fun testTransitiveViewEmptyStateReportsScanWithoutAnyFindings() {
        val view = TransitiveVulnerabilitiesView(project)

        view.update(
            advisoriesByCoordinate = emptyMap(),
            transitiveCoordinates = emptySet(),
            scanPerformed = true,
            hasDirectFindings = false
        )

        assertEquals(
            "Ein Scan ohne jeden Befund muss dies im Empty State melden",
            "No vulnerabilities found in the last scan.",
            view.table.emptyText.text
        )
    }

    fun testTransitiveViewEmptyStateReportsOnlyDirectFindings() {
        val view = TransitiveVulnerabilitiesView(project)

        view.update(
            advisoriesByCoordinate = mapOf(
                "com.example:direct:1.0.0" to listOf(
                    VulnerabilityAdvisory(id = "CVE-2", severity = VulnerabilitySeverity.HIGH, sources = setOf("OSV"))
                )
            ),
            transitiveCoordinates = emptySet(),
            scanPerformed = true,
            hasDirectFindings = true
        )

        assertEquals(
            "Nur direkte Befunde müssen im Empty State erklärt werden",
            "No transitive vulnerabilities found. All findings affect directly declared dependencies.",
            view.table.emptyText.text
        )
    }

    fun testShowDirectVulnerabilitiesInDependenciesFiltersMainTable() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        toolWindow.setTransitiveViewVisible(true)

        toolWindow.showDirectVulnerabilitiesInDependencies()

        assertFalse(
            "Der Link muss zurück in den Tab Dependencies wechseln",
            toolWindow.isTransitiveViewVisible()
        )
        assertEquals(
            "Der Link muss auf die selbst betroffenen Zeilen filtern",
            VulnerabilityFilter.SELF_VULNERABLE,
            toolWindow.vulnerabilitiesFilterComboBox.selectedItem
        )
    }

    fun testRefreshStaysAvailableWhileTransitiveViewIsShown() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertTrue(
            "In der Haupttabelle muss die kombinierte Aktualisierung verfügbar sein",
            toolWindow.isRefreshEnabled()
        )
        assertEquals(
            "Refresh and Search for New Versions",
            MyMessageBundle.message("toolwindow.MyToolWindow.refresh.button")
        )

        val coordinate = "com.example:transitive:2.0.0"
        val coords = toolWindow.javaClass.getDeclaredField("transitiveCoordinates")
            .apply { isAccessible = true }
            .get(toolWindow)
        @Suppress("UNCHECKED_CAST")
        (coords as MutableSet<String>).add(coordinate)
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }
            .get(toolWindow)
        @Suppress("UNCHECKED_CAST")
        (advisories as MutableMap<String, List<VulnerabilityAdvisory>>)[coordinate] = listOf(
            VulnerabilityAdvisory(
                id = "CVE-TRANSITIVE",
                severity = VulnerabilitySeverity.HIGH,
                sources = setOf("OSV")
            )
        )
        toolWindow.updateTransitiveVulnerabilitiesView()
        toolWindow.setTransitiveViewVisible(true)

        assertTrue(
            "Auch in der transitiven Ansicht muss die kombinierte Aktualisierung ausführbar sein",
            toolWindow.isRefreshEnabled()
        )

        toolWindow.setTransitiveViewVisible(false)
        assertTrue(
            "Nach dem Zurückschalten muss die kombinierte Aktualisierung verfügbar bleiben",
            toolWindow.isRefreshEnabled()
        )
    }

    fun testAutoVersionSearchFollowsSetting() {
        val settings = MavenUpSettings.getInstance()
        val previous = settings.state.autoSearchVersions
        try {
            val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
            toolWindow.getContent()

            settings.state.autoSearchVersions = true
            assertTrue(
                "Bei aktivierter Einstellung muss die automatische Versionssuche greifen",
                toolWindow.isAutoVersionSearchEnabled()
            )

            settings.state.autoSearchVersions = false
            assertFalse(
                "Bei deaktivierter Einstellung darf nicht automatisch gesucht werden",
                toolWindow.isAutoVersionSearchEnabled()
            )
        } finally {
            settings.state.autoSearchVersions = previous
        }
    }

    fun testRefreshTooltipDescribesCombinedBehaviour() {
        val tooltip = MyMessageBundle.message("toolwindow.MyToolWindow.refresh.tooltip")
        assertTrue(
            "Der Tooltip muss das Neuladen der pom.xml-Dateien benennen",
            tooltip.contains("pom.xml")
        )
        assertTrue(
            "Der Tooltip muss die anschließende Versionssuche benennen",
            tooltip.contains("new versions")
        )
    }

    fun testTransitiveVulnerabilitiesAbsentWithoutScan() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertFalse(
            "Ohne Scan-Ergebnisse sollten keine transitiven Sicherheitslücken vorliegen",
            toolWindow.hasTransitiveVulnerabilities()
        )
    }

    fun testTransitiveViewVisibilityWorksWithoutBoundTabs() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        addTransitiveFinding(toolWindow)
        toolWindow.updateTransitiveVulnerabilitiesView()

        assertTrue(toolWindow.hasTransitiveVulnerabilities())

        val showingField = toolWindow.javaClass.getDeclaredField("showingTransitiveView")
            .apply { isAccessible = true }

        toolWindow.setTransitiveViewVisible(true)
        assertTrue("Die transitive Ansicht sollte aktiv sein", showingField.getBoolean(toolWindow))

        toolWindow.setTransitiveViewVisible(false)
        assertFalse("Die Ansicht sollte zurück zur Haupttabelle wechseln", showingField.getBoolean(toolWindow))
    }

    fun testToolbarActionsTargetTransitiveViewWhenActive() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val coordinate = "com.example:transitive:2.0.0"
        val coords = toolWindow.javaClass.getDeclaredField("transitiveCoordinates")
            .apply { isAccessible = true }.get(toolWindow) as MutableSet<String>
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>
        coords.add(coordinate)
        advisories[coordinate] = listOf(
            VulnerabilityAdvisory(id = "CVE-1", severity = VulnerabilitySeverity.HIGH, sources = setOf("OSV"))
        )
        toolWindow.updateTransitiveVulnerabilitiesView()
        toolWindow.setTransitiveViewVisible(true)

        // Without a selection in the transitive view the actions are disabled.
        assertFalse(toolWindow.isOpenInRepositoryEnabled())
        assertFalse(toolWindow.isVulnerabilityDetailsEnabled())
        assertFalse(toolWindow.isDependencyHierarchyEnabled())

        val view = toolWindow.javaClass.getDeclaredField("transitiveVulnerabilitiesView")
            .apply { isAccessible = true }.get(toolWindow) as TransitiveVulnerabilitiesView
        view.table.setRowSelectionInterval(0, 0)

        // With a vulnerable transitive row selected both actions become available.
        assertTrue(toolWindow.isOpenInRepositoryEnabled())
        assertTrue(toolWindow.isVulnerabilityDetailsEnabled())
        assertTrue(toolWindow.isDependencyHierarchyEnabled())
    }

    fun testBulkAndResetActionsTargetTransitiveViewWhenActive() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val coordinate = "com.example:transitive:2.0.0"
        val coords = toolWindow.javaClass.getDeclaredField("transitiveCoordinates")
            .apply { isAccessible = true }.get(toolWindow) as MutableSet<String>
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>
        @Suppress("UNCHECKED_CAST")
        val transitiveVersions = toolWindow.javaClass.getDeclaredField("transitiveAvailableVersions")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<String>>
        coords.add(coordinate)
        advisories[coordinate] = listOf(
            VulnerabilityAdvisory(
                id = "CVE-1",
                severity = VulnerabilitySeverity.HIGH,
                sources = setOf("OSV"),
                fixedVersions = setOf("2.0.4")
            )
        )
        transitiveVersions["com.example:transitive"] = listOf("2.0.4", "2.0.0")
        toolWindow.updateTransitiveVulnerabilitiesView()
        toolWindow.setTransitiveViewVisible(true)

        assertTrue(toolWindow.isBulkVersionSelectionEnabledForCurrentView())
        assertTrue(toolWindow.isRecommendedSelectionEnabledForCurrentView())
        assertFalse(toolWindow.isResetVersionsEnabledForCurrentView())

        val view = toolWindow.javaClass.getDeclaredField("transitiveVulnerabilitiesView")
            .apply { isAccessible = true }.get(toolWindow) as TransitiveVulnerabilitiesView
        view.selectRecommendedVersionForAll()
        assertEquals("2.0.4", view.selectedVersions["com.example:transitive"])
        assertTrue(toolWindow.isResetVersionsEnabledForCurrentView())
    }

    /**
     * Stellt sicher, dass die Empfehlung einer direkten Abhängigkeit aus deren eigenen Warnungen
     * ermittelt, auf die abrufbaren Versionen abgebildet und über Kontext- und Sammelmenü angeboten wird.
     */
    fun testRecommendedVersionIsOfferedForDirectVulnerabilities() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val key = "com.example:direct"
        val knownDependencies = toolWindow.javaClass.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, String>
        val availableVersions = toolWindow.javaClass.getDeclaredField("availableVersions")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<String>>
        val selectedVersions = toolWindow.javaClass.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, String>
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>

        knownDependencies[key] = "1.0.0"
        availableVersions[key] = listOf("2.0.0", "1.2.0", "1.0.0")

        assertFalse("Ohne eigene Warnungen darf keine Empfehlung angeboten werden",
            toolWindow.hasRecommendedVersionForDependency(key))
        assertFalse(toolWindow.hasRecommendedVersions())
        assertFalse(toolWindow.isRecommendedSelectionEnabledForCurrentView())

        advisories["$key:1.0.0"] = listOf(
            VulnerabilityAdvisory(
                id = "CVE-2",
                severity = VulnerabilitySeverity.HIGH,
                sources = setOf("OSV"),
                fixedVersions = setOf("1.2.0")
            )
        )

        assertEquals("1.2.0", toolWindow.recommendedVersionForDependency(key))
        assertTrue(toolWindow.hasRecommendedVersionForDependency(key))
        assertTrue(toolWindow.hasRecommendedVersions())
        assertTrue(toolWindow.isRecommendedSelectionEnabledForCurrentView())

        toolWindow.selectRecommendedVersionForDependency(key)
        assertEquals("1.2.0", selectedVersions[key])
    }

    /**
     * Stellt sicher, dass rein transitive Warnungen einer Abhängigkeit keine Empfehlung für die
     * direkte Abhängigkeit selbst auslösen.
     */
    fun testRecommendedVersionIsAbsentForOnlyTransitiveVulnerabilities() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val key = "com.example:direct"
        val knownDependencies = toolWindow.javaClass.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, String>
        val availableVersions = toolWindow.javaClass.getDeclaredField("availableVersions")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<String>>
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>

        knownDependencies[key] = "1.0.0"
        availableVersions[key] = listOf("2.0.0", "1.2.0", "1.0.0")
        advisories["com.example:transitive:3.0.0"] = listOf(
            VulnerabilityAdvisory(
                id = "CVE-3",
                severity = VulnerabilitySeverity.HIGH,
                sources = setOf("OSV"),
                fixedVersions = setOf("3.0.1")
            )
        )

        assertEquals("", toolWindow.recommendedVersionForDependency(key))
        assertFalse(toolWindow.hasRecommendedVersions())
    }

    /**
     * Stellt sicher, dass ohne abgerufene Versionsliste keine Empfehlung angeboten wird, da die
     * Auswahl in der Spalte „New Version" dann nicht übernommen werden kann.
     */
    fun testRecommendedVersionRequiresFetchedVersions() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val key = "com.example:direct"
        val knownDependencies = toolWindow.javaClass.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, String>
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>

        knownDependencies[key] = "1.0.0"
        advisories["$key:1.0.0"] = listOf(
            VulnerabilityAdvisory(
                id = "CVE-4",
                severity = VulnerabilitySeverity.HIGH,
                sources = setOf("OSV"),
                fixedVersions = setOf("1.2.0")
            )
        )

        assertEquals("", toolWindow.recommendedVersionForDependency(key))
        assertFalse(toolWindow.hasRecommendedVersionForDependency(key))
    }

    fun testTransitiveTabTitleDropsCountWhenFindingsCleared() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val coords = toolWindow.javaClass.getDeclaredField("transitiveCoordinates")
            .apply { isAccessible = true }.get(toolWindow) as MutableSet<String>
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>

        addTransitiveFinding(toolWindow)
        toolWindow.updateTransitiveVulnerabilitiesView()
        assertEquals(1, toolWindow.transitiveVulnerabilityCount())

        coords.clear()
        advisories.clear()
        toolWindow.updateTransitiveVulnerabilitiesView()

        assertEquals(
            "Ohne verbleibende Funde darf der Zähler nicht mehr gemeldet werden",
            0,
            toolWindow.transitiveVulnerabilityCount()
        )
    }

    fun testApplySelectLatestVersionSettingSelectsNewest() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val settings = MavenUpSettings.getInstance()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:apply-test"
        availableVersions[key] = listOf("2.0.0", "1.5.0", "1.0.0")
        knownDependencies[key] = "1.0.0"

        // With LATEST mode enabled, the newest should be selected
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
        toolWindow.applySelectLatestVersionSetting()
        assertEquals("2.0.0", selectedVersions[key])

        // With DISABLED mode, the current version should be selected
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
        toolWindow.applySelectLatestVersionSetting()
        assertEquals("1.0.0", selectedVersions[key])

        // Reset
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
    }

    fun testApplySelectLatestVersionSettingSelectsLatestMinorWhenConfigured() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val settings = MavenUpSettings.getInstance()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:minor-test"
        availableVersions[key] = listOf("3.0.0", "2.9.9", "2.7.5", "2.5.0")
        knownDependencies[key] = "2.5.0"

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST_MINOR

        toolWindow.applySelectLatestVersionSetting()

        assertEquals("2.9.9", selectedVersions[key])

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
    }

    fun testApplySelectLatestVersionSettingDoesNotSelectOtherMajorLinesWhenNoSameMajorExists() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val settings = MavenUpSettings.getInstance()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:major-fallback"
        availableVersions[key] = listOf("3.2.0", "3.1.0")
        knownDependencies[key] = "2.8.0"

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST_MINOR

        toolWindow.applySelectLatestVersionSetting()

        assertNull(
            "Wenn keine Version derselben Major-Linie existiert, darf keine fremde Major-Version vorausgewählt werden",
            selectedVersions[key]
        )

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
    }

    fun testApplySelectLatestVersionSettingRemovesSelectionWhenAlreadyLatest() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val settings = MavenUpSettings.getInstance()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:already-latest"
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "2.0.0"
        selectedVersions[key] = "2.0.0"

        // When current version is already the latest and selectLatest is enabled,
        // the entry should be removed from selectedVersions (no change needed)
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
        toolWindow.applySelectLatestVersionSetting()
        assertNull(
            "Wenn die aktuelle Version bereits die neueste ist, soll kein Eintrag in selectedVersions stehen",
            selectedVersions[key]
        )

        // Reset
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
    }

    fun testApplySelectLatestVersionSettingDoesNothingWhenNoVersionsLoaded() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val settings = MavenUpSettings.getInstance()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        selectedVersions["com.example:existing"] = "1.0.0"

        // With no available versions loaded, the method should not modify selectedVersions
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
        toolWindow.applySelectLatestVersionSetting()
        assertEquals("1.0.0", selectedVersions["com.example:existing"])

        // Reset
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
    }

    fun testSettingsChangeKeepsSelectionWhenSelectLatestUnchanged() {
        val settings = MavenUpSettings.getInstance()
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:lib"
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "1.0.0"
        selectedVersions[key] = "2.0.0"

        // Nur eine unabhängige Einstellung ändert sich -> Auswahl bleibt erhalten
        toolWindow.applySelectLatestVersionSettingIfChanged()

        assertEquals(
            "Eine unabhängige Einstellungsänderung darf die getroffene Auswahl nicht zurücksetzen",
            "2.0.0",
            selectedVersions[key]
        )

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
    }

    fun testSettingsChangeReappliesSelectionWhenSelectLatestChanged() {
        val settings = MavenUpSettings.getInstance()
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:lib"
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "1.0.0"
        selectedVersions[key] = "1.0.0"

        // Die Einstellung VersionAutoSelectionMode wird tatsächlich geändert -> Auswahl wird neu berechnet
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
        toolWindow.applySelectLatestVersionSettingIfChanged()

        assertEquals(
            "Beim Ändern auf LATEST soll die neueste Version vorausgewählt werden",
            "2.0.0",
            selectedVersions[key]
        )

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
    }

    fun testSettingsChangeReappliesSelectionWhenSelectLatestMinorChanged() {
        val settings = MavenUpSettings.getInstance()
        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val key = "com.example:minor-setting-change"
        availableVersions[key] = listOf("3.0.0", "2.9.9", "2.6.0")
        knownDependencies[key] = "2.6.0"
        selectedVersions[key] = "3.0.0"

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.LATEST_MINOR
        toolWindow.applySelectLatestVersionSettingIfChanged()

        assertEquals(
            "Beim Ändern auf LATEST_MINOR soll eine passende Minor-Version vorausgewählt werden",
            "2.9.9",
            selectedVersions[key]
        )

        settings.state.versionAutoSelectionMode = VersionAutoSelectionMode.DISABLED
    }

    fun testChangesAndVulnerabilitiesFilterComboBoxDefaultsAndOptions() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertEquals(PendingChangesFilter.ALL, toolWindow.changesFilterComboBox.selectedItem)
        assertEquals(VulnerabilityFilter.ALL, toolWindow.vulnerabilitiesFilterComboBox.selectedItem)
        assertEquals(5, toolWindow.changesFilterComboBox.model.size)
        assertEquals(5, toolWindow.vulnerabilitiesFilterComboBox.model.size)
        assertEquals(PendingChangesFilter.ALL, toolWindow.changesFilterComboBox.model.getElementAt(0))
        assertEquals(PendingChangesFilter.ALL_CHANGES, toolWindow.changesFilterComboBox.model.getElementAt(1))
        assertEquals(PendingChangesFilter.WILL_UPDATE, toolWindow.changesFilterComboBox.model.getElementAt(2))
        assertEquals(PendingChangesFilter.WILL_REMOVE, toolWindow.changesFilterComboBox.model.getElementAt(3))
        assertEquals(PendingChangesFilter.UNCHANGED, toolWindow.changesFilterComboBox.model.getElementAt(4))
        assertEquals(VulnerabilityFilter.ALL, toolWindow.vulnerabilitiesFilterComboBox.model.getElementAt(0))
        assertEquals(VulnerabilityFilter.VULNERABLE, toolWindow.vulnerabilitiesFilterComboBox.model.getElementAt(1))
        assertEquals(VulnerabilityFilter.SELF_VULNERABLE, toolWindow.vulnerabilitiesFilterComboBox.model.getElementAt(2))
        assertEquals(
            VulnerabilityFilter.TRANSITIVE_VULNERABLE,
            toolWindow.vulnerabilitiesFilterComboBox.model.getElementAt(3)
        )
        assertEquals(VulnerabilityFilter.NOT_VULNERABLE, toolWindow.vulnerabilitiesFilterComboBox.model.getElementAt(4))
    }

    fun testRowFilterWithChangesAndVulnerabilitiesFilters() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)!!
        val model = table.model as DefaultTableModel

        val advisory = VulnerabilityAdvisory(
            id = "CVE-TEST",
            severity = VulnerabilitySeverity.HIGH,
            sources = setOf("OSV")
        )
        val vulnCell = buildVulnerabilityCell("com.example:vuln-lib:1.0.0", mapOf("com.example:vuln-lib:1.0.0" to listOf(advisory)), emptySet())
        val cleanCell = buildVulnerabilityCell("com.example:clean-lib:1.0.0", emptyMap(), emptySet())

        // Row 0: has changes, has vulnerabilities
        model.addRow(arrayOf("com.example", "vuln-lib", "", "dependency", vulnCell, "1.0.0", listOf("2.0.0", "1.0.0")))
        // Row 1: no changes, no vulnerabilities
        model.addRow(arrayOf("com.example", "clean-lib", "", "dependency", cleanCell, "1.0.0", listOf("1.0.0")))

        val fields = toolWindow.javaClass
        @Suppress("UNCHECKED_CAST")
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        selectedVersions["com.example:vuln-lib"] = "2.0.0"
        selectedVersions["com.example:clean-lib"] = "1.0.0"

        // Default: ALL/ALL -> both rows visible
        toolWindow.applyRowFilter()
        assertEquals(2, table.rowCount)

        // Changes filter: WILL_UPDATE -> only vuln-lib visible
        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.WILL_UPDATE
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("vuln-lib", table.getValueAt(0, 1))

        // Changes filter: UNCHANGED -> only clean-lib visible
        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.UNCHANGED
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("clean-lib", table.getValueAt(0, 1))

        // Reset changes filter to ALL, filter vulnerabilities: YES -> only vuln-lib visible
        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.ALL
        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.VULNERABLE
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("vuln-lib", table.getValueAt(0, 1))

        // Filter vulnerabilities: NO -> only clean-lib visible
        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.NOT_VULNERABLE
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("clean-lib", table.getValueAt(0, 1))
    }

    fun testRowFilterDistinguishesSelfAndTransitiveVulnerabilities() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)!!
        val model = table.model as DefaultTableModel

        val advisory = VulnerabilityAdvisory(
            id = "CVE-TEST",
            severity = VulnerabilitySeverity.HIGH,
            sources = setOf("OSV")
        )
        val selfCell = buildVulnerabilityCell(
            "com.example:self-lib:1.0.0",
            mapOf("com.example:self-lib:1.0.0" to listOf(advisory)),
            emptySet()
        )
        val transitiveCell = buildVulnerabilityCell(
            "com.example:transitive-lib:1.0.0",
            mapOf("com.example:child:1.0.0" to listOf(advisory)),
            setOf("com.example:child:1.0.0")
        )
        val bothCell = buildVulnerabilityCell(
            "com.example:both-lib:1.0.0",
            mapOf(
                "com.example:both-lib:1.0.0" to listOf(advisory),
                "com.example:child:1.0.0" to listOf(advisory)
            ),
            setOf("com.example:child:1.0.0")
        )
        val cleanCell = buildVulnerabilityCell("com.example:clean-lib:1.0.0", emptyMap(), emptySet())

        model.addRow(arrayOf("com.example", "self-lib", "", "dependency", selfCell, "1.0.0", listOf("1.0.0")))
        model.addRow(
            arrayOf("com.example", "transitive-lib", "", "dependency", transitiveCell, "1.0.0", listOf("1.0.0"))
        )
        model.addRow(arrayOf("com.example", "both-lib", "", "dependency", bothCell, "1.0.0", listOf("1.0.0")))
        model.addRow(arrayOf("com.example", "clean-lib", "", "dependency", cleanCell, "1.0.0", listOf("1.0.0")))

        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.VULNERABLE
        toolWindow.applyRowFilter()
        assertEquals(3, table.rowCount)

        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.SELF_VULNERABLE
        toolWindow.applyRowFilter()
        assertEquals(2, table.rowCount)
        assertEquals("self-lib", table.getValueAt(0, 1))
        assertEquals("both-lib", table.getValueAt(1, 1))

        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.TRANSITIVE_VULNERABLE
        toolWindow.applyRowFilter()
        assertEquals(2, table.rowCount)
        assertEquals("transitive-lib", table.getValueAt(0, 1))
        assertEquals("both-lib", table.getValueAt(1, 1))

        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.NOT_VULNERABLE
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("clean-lib", table.getValueAt(0, 1))
    }

    fun testFilterControlsHaveTooltips() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertEquals(
            MyMessageBundle.message("toolwindow.MyToolWindow.filter.type.tooltip"),
            toolWindow.typeFilterComboBox.toolTipText
        )
        assertEquals(
            MyMessageBundle.message("toolwindow.MyToolWindow.filter.changes.tooltip"),
            toolWindow.changesFilterComboBox.toolTipText
        )
        assertEquals(
            MyMessageBundle.message("toolwindow.MyToolWindow.filter.vulnerabilities.tooltip"),
            toolWindow.vulnerabilitiesFilterComboBox.toolTipText
        )
        assertEquals(
            MyMessageBundle.message("toolwindow.MyToolWindow.filter.search.tooltip"),
            toolWindow.searchTextField.toolTipText
        )
    }

    fun testResetFiltersEnabledReflectsActiveFilters() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertFalse(toolWindow.isResetFiltersEnabled())

        toolWindow.searchTextField.text = "example"
        assertTrue(toolWindow.isResetFiltersEnabled())
        toolWindow.searchTextField.text = ""
        assertFalse(toolWindow.isResetFiltersEnabled())

        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.WILL_UPDATE
        assertTrue(toolWindow.isResetFiltersEnabled())
        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.ALL
        assertFalse(toolWindow.isResetFiltersEnabled())

        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.NOT_VULNERABLE
        assertTrue(toolWindow.isResetFiltersEnabled())
        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.ALL
        assertFalse(toolWindow.isResetFiltersEnabled())

        toolWindow.versionSourceFilterComboBox.selectedItem = TriStateFilter.NO
        assertTrue(toolWindow.isResetFiltersEnabled())
        toolWindow.versionSourceFilterComboBox.selectedItem = TriStateFilter.ALL
        assertFalse(toolWindow.isResetFiltersEnabled())
    }

    fun testVersionSourceFilterIsDisabledWithoutInheritedRows() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertFalse(toolWindow.isVersionSourceFilterAvailable())
        assertFalse(toolWindow.versionSourceFilterComboBox.isEnabled)

        toolWindow.inheritedVersionDependencies.add("com.example:lib-a")
        toolWindow.updateVersionSourceFilterState()

        assertTrue(toolWindow.isVersionSourceFilterAvailable())
        assertTrue(toolWindow.versionSourceFilterComboBox.isEnabled)
    }

    fun testVersionSourceFilterResetsSelectionWhenUnavailable() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        toolWindow.inheritedVersionDependencies.add("com.example:lib-a")
        toolWindow.updateVersionSourceFilterState()
        toolWindow.versionSourceFilterComboBox.selectedItem = TriStateFilter.YES

        toolWindow.inheritedVersionDependencies.clear()
        toolWindow.updateVersionSourceFilterState()

        assertFalse(toolWindow.versionSourceFilterComboBox.isEnabled)
        assertEquals(TriStateFilter.ALL, toolWindow.versionSourceFilterComboBox.selectedItem)
    }

    fun testVersionSourceFilterHidesInheritedAndDeclaredRows() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)!!
        val model = table.model as DefaultTableModel

        model.addRow(arrayOf("com.example", "inherited", "", "dependency", null, "1.0.0", listOf("1.0.0")))
        model.addRow(arrayOf("com.example", "declared", "", "dependency", null, "2.0.0", listOf("2.0.0")))
        toolWindow.inheritedVersionDependencies.add("com.example:inherited")
        toolWindow.updateVersionSourceFilterState()

        toolWindow.versionSourceFilterComboBox.selectedItem = TriStateFilter.NO
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("declared", table.getValueAt(0, ARTIFACT_ID_COLUMN))

        toolWindow.versionSourceFilterComboBox.selectedItem = TriStateFilter.YES
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("inherited", table.getValueAt(0, ARTIFACT_ID_COLUMN))

        toolWindow.versionSourceFilterComboBox.selectedItem = TriStateFilter.ALL
        toolWindow.applyRowFilter()
        assertEquals(2, table.rowCount)
    }

    fun testFilterByReplacesSearchTextAndAppliesFilter() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)!!
        val model = table.model as DefaultTableModel

        model.addRow(arrayOf("com.example", "lib-a", "", "dependency", null, "1.0.0", listOf("1.0.0")))
        model.addRow(arrayOf("org.other", "lib-b", "", "dependency", null, "1.0.0", listOf("1.0.0")))

        toolWindow.searchTextField.text = "org.other"
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)

        toolWindow.filterBy("com.example")

        assertEquals("com.example", toolWindow.searchTextField.text)
        assertEquals(1, table.rowCount)
        assertEquals("com.example", table.getValueAt(0, GROUP_ID_COLUMN))
        assertTrue(toolWindow.isResetFiltersEnabled())
    }

    fun testResetAllFiltersRestoresDefaultsAndShowsAllRows() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val table = findTable(content)!!
        val model = table.model as DefaultTableModel

        model.addRow(arrayOf("com.example", "lib-a", "", "dependency", null, "1.0.0", listOf("1.0.0")))
        model.addRow(arrayOf("com.example", "lib-b", "", "plugin", null, "1.0.0", listOf("1.0.0")))

        toolWindow.updateTypeFilterOptions()
        toolWindow.inheritedVersionDependencies.add("com.example:lib-a")
        toolWindow.updateVersionSourceFilterState()
        toolWindow.searchTextField.text = "lib-a"
        toolWindow.typeFilterComboBox.selectedItem = "dependency"
        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.UNCHANGED
        toolWindow.vulnerabilitiesFilterComboBox.selectedItem = VulnerabilityFilter.NOT_VULNERABLE
        toolWindow.versionSourceFilterComboBox.selectedItem = TriStateFilter.YES
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)

        toolWindow.resetAllFilters()

        assertEquals("", toolWindow.searchTextField.text)
        assertEquals(PendingChangesFilter.ALL, toolWindow.changesFilterComboBox.selectedItem)
        assertEquals(VulnerabilityFilter.ALL, toolWindow.vulnerabilitiesFilterComboBox.selectedItem)
        assertEquals(TriStateFilter.ALL, toolWindow.versionSourceFilterComboBox.selectedItem)
        assertFalse(toolWindow.isResetFiltersEnabled())
        assertEquals(2, table.rowCount)
    }

    /**
     * Liefert die drei per Reflection zugänglichen Versions-Maps eines Tool-Windows.
     */
    @Suppress("UNCHECKED_CAST")
    private fun versionMaps(
        toolWindow: MavenUpWindowFactory.MyToolWindow
    ): Triple<MutableMap<String, List<String>>, MutableMap<String, String>, MutableMap<String, String>> {
        val fields = toolWindow.javaClass
        val availableVersions = fields.getDeclaredField("availableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>
        val selectedVersions = fields.getDeclaredField("selectedVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        val knownDependencies = fields.getDeclaredField("knownDependencies")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        return Triple(availableVersions, selectedVersions, knownDependencies)
    }

    fun testSelectHighestMajorVersionForAllSelectsNewestOverall() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:major-all"
        availableVersions[key] = listOf("3.1.0", "3.0.0", "2.9.9", "2.5.0")
        knownDependencies[key] = "2.5.0"
        model.addRow(arrayOf("com.example", "major-all", "", "dependency", null, "2.5.0", availableVersions[key]))
        toolWindow.applyRowFilter()

        toolWindow.selectHighestMajorVersionForAll(visibleOnly = true)

        assertEquals("3.1.0", selectedVersions[key])
    }

    fun testSelectHighestMinorVersionForAllStaysWithinCurrentMajor() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:minor-all"
        availableVersions[key] = listOf("3.1.0", "3.0.0", "2.9.9", "2.5.0")
        knownDependencies[key] = "2.5.0"
        model.addRow(arrayOf("com.example", "minor-all", "", "dependency", null, "2.5.0", availableVersions[key]))
        toolWindow.applyRowFilter()

        toolWindow.selectHighestMinorVersionForAll(visibleOnly = true)

        assertEquals("2.9.9", selectedVersions[key])
    }

    fun testSelectHighestMinorVersionForAllKeepsCurrentWhenNoSameMajorExists() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:minor-none"
        availableVersions[key] = listOf("3.2.0", "3.1.0")
        knownDependencies[key] = "2.8.0"
        model.addRow(arrayOf("com.example", "minor-none", "", "dependency", null, "2.8.0", availableVersions[key]))
        toolWindow.applyRowFilter()

        toolWindow.selectHighestMinorVersionForAll(visibleOnly = true)

        assertNull(
            "Ohne Version derselben Major-Linie darf keine abweichende Auswahl gesetzt werden.",
            selectedVersions[key]
        )
    }

    fun testSelectHighestMajorVersionForDependencySelectsNewestOverall() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:major-row"
        val other = "com.example:other-row"
        availableVersions[key] = listOf("3.1.0", "3.0.0", "2.9.9", "2.5.0")
        availableVersions[other] = listOf("9.0.0", "8.0.0")
        knownDependencies[key] = "2.5.0"
        knownDependencies[other] = "8.0.0"
        model.addRow(arrayOf("com.example", "major-row", "", "dependency", null, "2.5.0", availableVersions[key]))
        model.addRow(arrayOf("com.example", "other-row", "", "dependency", null, "8.0.0", availableVersions[other]))
        toolWindow.applyRowFilter()

        toolWindow.selectHighestMajorVersionForDependency(key)

        assertEquals("3.1.0", selectedVersions[key])
        assertNull(
            "Nur die per Rechtsklick gewählte Dependency darf geändert werden.",
            selectedVersions[other]
        )
    }

    fun testSelectHighestMinorVersionForDependencyStaysWithinCurrentMajor() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:minor-row"
        availableVersions[key] = listOf("3.1.0", "3.0.0", "2.9.9", "2.5.0")
        knownDependencies[key] = "2.5.0"
        model.addRow(arrayOf("com.example", "minor-row", "", "dependency", null, "2.5.0", availableVersions[key]))
        toolWindow.applyRowFilter()

        toolWindow.selectHighestMinorVersionForDependency(key)

        assertEquals("2.9.9", selectedVersions[key])
    }

    fun testSelectHighestMinorVersionForDependencyKeepsCurrentWhenNoSameMajorExists() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:minor-row-none"
        availableVersions[key] = listOf("3.2.0", "3.1.0")
        knownDependencies[key] = "2.8.0"
        model.addRow(arrayOf("com.example", "minor-row-none", "", "dependency", null, "2.8.0", availableVersions[key]))
        toolWindow.applyRowFilter()

        toolWindow.selectHighestMinorVersionForDependency(key)

        assertNull(
            "Ohne Version derselben Major-Linie darf keine abweichende Auswahl gesetzt werden.",
            selectedVersions[key]
        )
    }

    fun testSelectHighestVersionForDependencyDoesNothingWithoutFetchedVersions() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (_, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:no-versions"
        knownDependencies[key] = "1.0.0"
        model.addRow(arrayOf("com.example", "no-versions", "", "dependency", null, "1.0.0", emptyList<String>()))
        toolWindow.applyRowFilter()

        assertFalse(
            "Ohne abgerufene Versionen dürfen die Kontextmenü-Aktionen nicht verfügbar sein.",
            toolWindow.hasSelectableVersionsForDependency(key)
        )

        toolWindow.selectHighestMajorVersionForDependency(key)
        toolWindow.selectHighestMinorVersionForDependency(key)

        assertNull(
            "Ohne verfügbare Versionen darf keine Auswahl gesetzt werden.",
            selectedVersions[key]
        )
    }

    fun testHasSelectableVersionsForDependencyReflectsAvailability() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val (availableVersions, _, _) = versionMaps(toolWindow)

        val key = "com.example:availability"
        assertFalse(toolWindow.hasSelectableVersionsForDependency(key))

        availableVersions[key] = listOf("1.1.0", "1.0.0")
        assertTrue(toolWindow.hasSelectableVersionsForDependency(key))
    }

    fun testResetVersionForDependencyRestoresCurrentForRowOnly() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:reset-row"
        val other = "com.example:reset-other"
        availableVersions[key] = listOf("3.1.0", "2.5.0")
        availableVersions[other] = listOf("9.0.0", "8.0.0")
        knownDependencies[key] = "2.5.0"
        knownDependencies[other] = "8.0.0"
        selectedVersions[key] = "3.1.0"
        selectedVersions[other] = "9.0.0"
        model.addRow(arrayOf("com.example", "reset-row", "", "dependency", null, "2.5.0", availableVersions[key]))
        model.addRow(arrayOf("com.example", "reset-other", "", "dependency", null, "8.0.0", availableVersions[other]))
        toolWindow.applyRowFilter()

        assertTrue(toolWindow.isVersionResetEnabledForDependency(key, "dependency"))
        toolWindow.resetVersionForDependency(key, "dependency")

        assertNull("Die angeklickte Dependency muss zurückgesetzt werden.", selectedVersions[key])
        assertEquals(
            "Andere Dependencies dürfen nicht zurückgesetzt werden.",
            "9.0.0",
            selectedVersions[other]
        )
        assertFalse(toolWindow.isVersionResetEnabledForDependency(key, "dependency"))
    }

    fun testResetVersionForDependencyClearsPropertyLinkedEntries() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        findTable(content)
        val (_, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        @Suppress("UNCHECKED_CAST")
        val dependencyToProperty = toolWindow.javaClass.getDeclaredField("dependencyToProperty")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>

        val keyA = "com.example:prop-a"
        val keyB = "com.example:prop-b"
        knownDependencies[keyA] = "1.0.0"
        knownDependencies[keyB] = "1.0.0"
        dependencyToProperty[keyA] = "shared.version"
        dependencyToProperty[keyB] = "shared.version"
        selectedVersions[keyA] = "2.0.0"
        selectedVersions[keyB] = "2.0.0"

        toolWindow.resetVersionForDependency(keyA, "dependency")

        assertNull(selectedVersions[keyA])
        assertNull(
            "Property-verknüpfte Einträge müssen ebenfalls zurückgesetzt werden.",
            selectedVersions[keyB]
        )
    }

    fun testIsVersionResetEnabledForDependencyFalseWithoutPendingChange() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val (_, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:reset-none"
        knownDependencies[key] = "1.0.0"
        assertFalse(
            "Ohne Auswahl darf das Zurücksetzen nicht verfügbar sein.",
            toolWindow.isVersionResetEnabledForDependency(key, "dependency")
        )

        selectedVersions[key] = "1.0.0"
        assertFalse(
            "Wenn die Auswahl der aktuellen Version entspricht, ist kein Zurücksetzen nötig.",
            toolWindow.isVersionResetEnabledForDependency(key, "dependency")
        )
    }

    fun testBulkSelectionSkipsRowsHiddenByFilter() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val visibleKey = "com.example:visible-lib"
        val hiddenKey = "com.example:hidden-lib"
        availableVersions[visibleKey] = listOf("2.0.0", "1.0.0")
        availableVersions[hiddenKey] = listOf("2.0.0", "1.0.0")
        knownDependencies[visibleKey] = "1.0.0"
        knownDependencies[hiddenKey] = "1.0.0"
        model.addRow(arrayOf("com.example", "visible-lib", "", "dependency", null, "1.0.0", availableVersions[visibleKey]))
        model.addRow(arrayOf("com.example", "hidden-lib", "", "plugin", null, "1.0.0", availableVersions[hiddenKey]))

        // Nur Zeilen vom Typ "dependency" sichtbar lassen.
        toolWindow.updateTypeFilterOptions()
        toolWindow.typeFilterComboBox.selectedItem = "dependency"
        toolWindow.applyRowFilter()
        assertTrue(toolWindow.isRowFilterHidingEntries())

        toolWindow.selectHighestMajorVersionForAll(visibleOnly = true)

        assertEquals("2.0.0", selectedVersions[visibleKey])
        assertNull(
            "Eine ausgefilterte Zeile darf durch die Sammelauswahl nicht verändert werden.",
            selectedVersions[hiddenKey]
        )
    }

    fun testResetAllVersionsToCurrentClearsHiddenSelections() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val visibleKey = "com.example:reset-visible"
        val hiddenKey = "com.example:reset-hidden"
        availableVersions[visibleKey] = listOf("2.0.0", "1.0.0")
        availableVersions[hiddenKey] = listOf("2.0.0", "1.0.0")
        knownDependencies[visibleKey] = "1.0.0"
        knownDependencies[hiddenKey] = "1.0.0"
        selectedVersions[visibleKey] = "2.0.0"
        selectedVersions[hiddenKey] = "2.0.0"
        model.addRow(arrayOf("com.example", "reset-visible", "", "dependency", null, "1.0.0", availableVersions[visibleKey]))
        model.addRow(arrayOf("com.example", "reset-hidden", "", "plugin", null, "1.0.0", availableVersions[hiddenKey]))

        toolWindow.updateTypeFilterOptions()
        toolWindow.typeFilterComboBox.selectedItem = "dependency"
        toolWindow.applyRowFilter()

        toolWindow.resetAllVersionsToCurrent()

        assertNull(
            "Das Zurücksetzen muss auch ausgefilterte Auswahlen entfernen.",
            selectedVersions[hiddenKey]
        )
        assertNull(selectedVersions[visibleKey])
    }

    fun testResetVisibleVersionsToCurrentKeepsHiddenSelections() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val visibleKey = "com.example:reset-visible"
        val hiddenKey = "com.example:reset-hidden"
        availableVersions[visibleKey] = listOf("2.0.0", "1.0.0")
        availableVersions[hiddenKey] = listOf("2.0.0", "1.0.0")
        knownDependencies[visibleKey] = "1.0.0"
        knownDependencies[hiddenKey] = "1.0.0"
        selectedVersions[visibleKey] = "2.0.0"
        selectedVersions[hiddenKey] = "2.0.0"
        model.addRow(arrayOf("com.example", "reset-visible", "", "dependency", null, "1.0.0", availableVersions[visibleKey]))
        model.addRow(arrayOf("com.example", "reset-hidden", "", "plugin", null, "1.0.0", availableVersions[hiddenKey]))

        toolWindow.updateTypeFilterOptions()
        toolWindow.typeFilterComboBox.selectedItem = "dependency"
        toolWindow.applyRowFilter()

        toolWindow.resetVisibleVersionsToCurrent()

        assertNull(
            "Das gefilterte Zurücksetzen muss die sichtbare Auswahl entfernen.",
            selectedVersions[visibleKey]
        )
        assertEquals(
            "Eine ausgefilterte Auswahl darf beim gefilterten Zurücksetzen nicht verändert werden.",
            "2.0.0",
            selectedVersions[hiddenKey]
        )
    }

    fun testBulkSelectionActionDescriptionAppendsHintWhenFilterHidesRows() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val content = toolWindow.getContent()
        val model = findTable(content)!!.model as DefaultTableModel

        model.addRow(arrayOf("com.example", "lib-a", "", "dependency", null, "1.0.0", listOf("1.0.0")))
        model.addRow(arrayOf("com.example", "lib-b", "", "plugin", null, "1.0.0", listOf("1.0.0")))

        toolWindow.applyRowFilter()
        assertFalse(toolWindow.isRowFilterHidingEntries())
        assertEquals("Base", toolWindow.bulkSelectionActionDescription("Base"))

        toolWindow.updateTypeFilterOptions()
        toolWindow.typeFilterComboBox.selectedItem = "dependency"
        toolWindow.applyRowFilter()
        assertTrue(toolWindow.isRowFilterHidingEntries())
        assertTrue(toolWindow.bulkSelectionActionDescription("Base").startsWith("Base"))
        assertTrue(toolWindow.bulkSelectionActionDescription("Base").length > "Base".length)
    }

    fun testTransitiveViewBulkSelectionActionDescriptionReflectsTransitiveFilter() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val showingTransitiveViewField = toolWindow.javaClass.getDeclaredField("showingTransitiveView")
            .apply { isAccessible = true }
        val transitiveViewField = toolWindow.javaClass.getDeclaredField("transitiveVulnerabilitiesView")
            .apply { isAccessible = true }
        val transitiveView = transitiveViewField.get(toolWindow) as TransitiveVulnerabilitiesView

        val coord1 = "org.test:lib-a:1.0.0"
        val coord2 = "org.test:lib-b:1.0.0"
        val advisories = mapOf(
            coord1 to listOf(
                VulnerabilityAdvisory(
                    id = "CVE-1",
                    severity = VulnerabilitySeverity.HIGH,
                    sources = setOf("OSV")
                )
            ),
            coord2 to listOf(
                VulnerabilityAdvisory(
                    id = "CVE-2",
                    severity = VulnerabilitySeverity.HIGH,
                    sources = setOf("OSV")
                )
            )
        )
        transitiveView.update(
            advisories,
            setOf(coord1, coord2),
            emptyMap(),
            mapOf("org.test:lib-a" to listOf("2.0.0", "1.0.0"), "org.test:lib-b" to listOf("2.0.0", "1.0.0"))
        )

        showingTransitiveViewField.setBoolean(toolWindow, true)
        assertFalse(toolWindow.isRowFilterHidingEntries())
        assertEquals("Base", toolWindow.bulkSelectionActionDescription("Base"))

        transitiveView.filterPanel.filterBy("lib-a")
        assertTrue(toolWindow.isRowFilterHidingEntries())
        assertTrue(toolWindow.bulkSelectionActionDescription("Base").startsWith("Base"))
        assertTrue(toolWindow.bulkSelectionActionDescription("Base").length > "Base".length)
    }

    fun testResetAllVersionsToCurrentClearsSelections() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:reset-all"
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "1.0.0"
        selectedVersions[key] = "2.0.0"

        toolWindow.resetAllVersionsToCurrent()

        assertNull(
            "Nach dem Zurücksetzen darf keine abweichende Auswahl mehr vorhanden sein.",
            selectedVersions[key]
        )
        assertFalse(toolWindow.hasSelectedUpdates())
    }

    fun testConfirmAndResetSkipsDialogWhenConfirmationDisabled() {
        val settings = MavenUpSettings.getInstance()
        val previous = settings.state.confirmVersionReset
        settings.state.confirmVersionReset = false
        try {
            val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
            toolWindow.getContent()
            val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

            val key = "com.example:reset-no-confirm"
            availableVersions[key] = listOf("2.0.0", "1.0.0")
            knownDependencies[key] = "1.0.0"
            selectedVersions[key] = "2.0.0"

            toolWindow.confirmAndResetAllVersionsToCurrent()

            assertNull(
                "Bei deaktivierter Bestätigung soll ohne Dialog zurückgesetzt werden.",
                selectedVersions[key]
            )
            assertFalse(toolWindow.hasSelectedUpdates())
        } finally {
            settings.state.confirmVersionReset = previous
        }
    }

    fun testBulkSelectionDoesNothingWhenNoVersionsLoaded() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val (_, selectedVersions, _) = versionMaps(toolWindow)

        selectedVersions["com.example:existing"] = "1.0.0"

        toolWindow.selectHighestMajorVersionForAll(visibleOnly = true)
        toolWindow.selectHighestMinorVersionForAll(visibleOnly = true)

        assertEquals("1.0.0", selectedVersions["com.example:existing"])
    }

    fun testBulkSelectionEnabledReflectsLoadedVersions() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val (availableVersions, _, _) = versionMaps(toolWindow)

        assertFalse(toolWindow.isBulkVersionSelectionEnabled())

        availableVersions["com.example:with-versions"] = listOf("1.0.0")

        assertTrue(toolWindow.isBulkVersionSelectionEnabled())
    }

    fun testResetVersionsEnabledReflectsPendingChanges() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:reset-enabled"
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "1.0.0"

        assertFalse(toolWindow.isResetVersionsEnabled())

        selectedVersions[key] = "2.0.0"

        assertTrue(toolWindow.isResetVersionsEnabled())
    }

    fun testManagedEntryRemovalIsCollectedAndCanBeUndone() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val key = "com.example:managed-library"
        val managedDependency = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)
        availableVersions[key] = listOf("2.0.0", "1.0.0")
        knownDependencies[key] = "1.0.0"

        toolWindow.markManagedEntryForRemoval(key, managedDependency, "1.0.0")

        assertTrue(toolWindow.isManagedEntryMarkedForRemoval(key, managedDependency))
        assertTrue(toolWindow.isUpdateActionEnabled())
        assertTrue(toolWindow.collectSelectedUpdates().single().removeFromPom)

        toolWindow.selectHighestMajorVersionForDependency(key)

        assertFalse(toolWindow.isManagedEntryMarkedForRemoval(key, managedDependency))
        assertEquals("2.0.0", selectedVersions[key])
    }

    fun testMarkingManagedEntryForRemovalPreservesPropertyLinkedVersionSelections() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val managedDependency = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)
        @Suppress("UNCHECKED_CAST")
        val dependencyToProperty = toolWindow.javaClass.getDeclaredField("dependencyToProperty")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        @Suppress("UNCHECKED_CAST")
        val knownTypes = toolWindow.javaClass.getDeclaredField("knownTypes")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, String>
        val removalKey = "com.example:managed-removal"
        val linkedKey = "com.example:managed-linked"
        availableVersions[removalKey] = listOf("2.0.0", "1.0.0")
        availableVersions[linkedKey] = listOf("2.0.0", "1.0.0")
        knownDependencies[removalKey] = "1.0.0"
        knownDependencies[linkedKey] = "1.0.0"
        knownTypes[removalKey] = managedDependency
        knownTypes[linkedKey] = managedDependency
        dependencyToProperty[removalKey] = "shared.version"
        dependencyToProperty[linkedKey] = "shared.version"
        selectedVersions[removalKey] = "2.0.0"
        selectedVersions[linkedKey] = "2.0.0"

        toolWindow.markManagedEntryForRemoval(removalKey, managedDependency, "1.0.0")

        assertNull(selectedVersions[removalKey])
        assertEquals("2.0.0", selectedVersions[linkedKey])
        assertTrue(
            toolWindow.collectSelectedUpdates().any {
                it.artifactId == "managed-linked" && !it.removeFromPom && it.newVersion == "2.0.0"
            }
        )

        // A synchronized property selection may reintroduce the marked coordinate.
        selectedVersions[removalKey] = "2.0.0"
        assertFalse(
            toolWindow.collectSelectedUpdates().any {
                it.artifactId == "managed-removal" && !it.removeFromPom
            }
        )
    }

    fun testManagedEntryRemovalShowsWillBeRemovedInNewVersionColumn() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val table = findTable(toolWindow.getContent())!!
        val model = table.model as DefaultTableModel
        val key = "com.example:managed-library"
        val managedDependency = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        model.addRow(
            arrayOf(
                "com.example", "managed-library", "", managedDependency, null, "1.0.0", listOf("1.0.0")
            )
        )

        toolWindow.markManagedEntryForRemoval(key, managedDependency, "1.0.0")

        val renderer = table.columnModel.getColumn(6).cellRenderer
        val component = renderer.getTableCellRendererComponent(
            table, model.getValueAt(0, 6), false, false, 0, 6
        ) as JLabel
        assertEquals(MyMessageBundle.message("toolwindow.MyToolWindow.version.willRemove"), component.text)
    }

    fun testPendingFilterDistinguishesManagedEntryRemovalFromVersionUpdate() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val table = findTable(toolWindow.getContent())!!
        val model = table.model as DefaultTableModel
        val managedDependency = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val removalKey = "com.example:managed-library"
        val updateKey = "com.example:regular-library"
        model.addRow(arrayOf("com.example", "managed-library", "", managedDependency, null, "1.0.0", listOf("1.0.0")))
        model.addRow(arrayOf("com.example", "regular-library", "", "dependency", null, "1.0.0", listOf("2.0.0", "1.0.0")))
        val (_, selectedVersions, knownDependencies) = versionMaps(toolWindow)
        knownDependencies[removalKey] = "1.0.0"
        knownDependencies[updateKey] = "1.0.0"
        selectedVersions[updateKey] = "2.0.0"
        toolWindow.markManagedEntryForRemoval(removalKey, managedDependency, "1.0.0")

        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.ALL_CHANGES
        toolWindow.applyRowFilter()
        assertEquals(2, table.rowCount)

        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.WILL_REMOVE
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("managed-library", table.getValueAt(0, ARTIFACT_ID_COLUMN))

        toolWindow.changesFilterComboBox.selectedItem = PendingChangesFilter.WILL_UPDATE
        toolWindow.applyRowFilter()
        assertEquals(1, table.rowCount)
        assertEquals("regular-library", table.getValueAt(0, ARTIFACT_ID_COLUMN))
    }

    fun testResetAllVersionsAlsoClearsManagedEntryRemovalMarks() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val managedDependency = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val dependencyKey = "com.example:managed-dependency"
        val pluginKey = "com.example:managed-plugin"

        toolWindow.markManagedEntryForRemoval(dependencyKey, managedDependency, "1.0.0")
        toolWindow.markManagedEntryForRemoval(pluginKey, MANAGED_PLUGIN, "2.0.0")
        toolWindow.resetAllVersionsToCurrent()

        assertFalse(toolWindow.isManagedEntryMarkedForRemoval(dependencyKey, managedDependency))
        assertFalse(toolWindow.isManagedEntryMarkedForRemoval(pluginKey, MANAGED_PLUGIN))
        assertFalse(toolWindow.hasSelectedUpdates())
    }

    fun testContextMenuResetKeepsRemovalMarkOfDifferentManagedEntryType() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val managedDependency = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val key = "com.example:shared-artifact"

        toolWindow.markManagedEntryForRemoval(key, managedDependency, "1.0.0")
        toolWindow.markManagedEntryForRemoval(key, MANAGED_PLUGIN, "1.0.0")
        toolWindow.resetVersionForDependency(key, managedDependency)

        assertFalse(toolWindow.isManagedEntryMarkedForRemoval(key, managedDependency))
        assertTrue(toolWindow.isManagedEntryMarkedForRemoval(key, MANAGED_PLUGIN))
    }

    /**
     * Stellt sicher, dass eine neuere Version als verfügbar erkannt wird und der Badge-Zustand
     * daraus abgeleitet werden kann.
     */
    fun testHasAvailableUpdatesDetectsNewerVersion() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val (availableVersions, _, knownDependencies) = versionMaps(toolWindow)

        val key = "com.example:badge-updates"
        knownDependencies[key] = "1.0.0"

        assertFalse("Ohne abgerufene Versionen darf kein Update gemeldet werden", toolWindow.hasAvailableUpdates())

        availableVersions[key] = listOf("2.0.0", "1.0.0")
        assertTrue("Eine höhere Version muss als Update gelten", toolWindow.hasAvailableUpdates())

        availableVersions[key] = listOf("1.0.0")
        assertFalse("Die aktuelle Version allein ist kein Update", toolWindow.hasAvailableUpdates())
    }

    /**
     * Stellt sicher, dass der höchste Schweregrad über alle Funde hinweg ermittelt wird und ohne
     * Funde `null` zurückgegeben wird.
     */
    fun testWorstVulnerabilitySeverityReturnsHighestFinding() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        assertNull("Ohne Scan-Ergebnis darf kein Schweregrad gemeldet werden", toolWindow.worstVulnerabilitySeverity())

        @Suppress("UNCHECKED_CAST")
        val advisories = toolWindow.javaClass.getDeclaredField("vulnerabilityAdvisories")
            .apply { isAccessible = true }.get(toolWindow) as MutableMap<String, List<VulnerabilityAdvisory>>
        advisories["com.example:low:1.0.0"] = listOf(
            VulnerabilityAdvisory(id = "CVE-LOW", severity = VulnerabilitySeverity.LOW, sources = setOf("OSV"))
        )
        advisories["com.example:critical:1.0.0"] = listOf(
            VulnerabilityAdvisory(id = "CVE-CRIT", severity = VulnerabilitySeverity.CRITICAL, sources = setOf("OSV"))
        )

        assertEquals(VulnerabilitySeverity.CRITICAL, toolWindow.worstVulnerabilitySeverity())
    }

    /**
     * Stellt sicher, dass die Badge-Aktualisierung auch ohne registriertes Tool-Window robust ist.
     */
    fun testUpdateToolWindowBadgeDoesNotThrow() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        toolWindow.updateToolWindowBadge()

        assertFalse("Projekt darf durch die Badge-Aktualisierung nicht disposed werden", project.isDisposed)
    }

    /**
     * Liefert die per Reflection zugängliche Map der ungefilterten Versionen eines Tool-Windows.
     */
    private fun rawVersionMap(toolWindow: MavenUpWindowFactory.MyToolWindow): MutableMap<String, List<String>> =
        toolWindow.javaClass.getDeclaredField("rawAvailableVersions")
            .apply { isAccessible = true }
            .get(toolWindow) as MutableMap<String, List<String>>

    /**
     * Führt [block] mit den angegebenen Anzeigeeinstellungen aus und stellt die vorherigen Werte
     * anschließend wieder her.
     */
    private fun <T> withVersionVisibilitySettings(
        hideUnstable: Boolean,
        offerAll: Boolean,
        block: () -> T
    ): T {
        val state = MavenUpSettings.getInstance().state
        val previousHide = state.hideUnstableVersions
        val previousOfferAll = state.offerAllVersions
        state.hideUnstableVersions = hideUnstable
        state.offerAllVersions = offerAll
        try {
            return block()
        } finally {
            state.hideUnstableVersions = previousHide
            state.offerAllVersions = previousOfferAll
        }
    }

    /**
     * Stellt sicher, dass das Aktivieren von "Hide unstable versions" die Spalte **New Version**
     * ohne erneute Versionssuche aktualisiert.
     */
    fun testApplyVersionVisibilitySettingsHidesUnstableVersionsImmediately() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val model = findTable(toolWindow.getContent())!!.model as DefaultTableModel
        val (availableVersions, _, knownDependencies) = versionMaps(toolWindow)
        val raw = rawVersionMap(toolWindow)

        val key = "com.example:unstable-lib"
        knownDependencies[key] = "1.0.0"
        raw[key] = listOf("2.0.0", "2.0.0-RC1", "1.0.0")
        availableVersions[key] = raw[key]!!
        model.addRow(arrayOf("com.example", "unstable-lib", "", "dependency", null, "1.0.0", raw[key]))

        withVersionVisibilitySettings(hideUnstable = true, offerAll = false) {
            toolWindow.applyVersionVisibilitySettings()
        }

        assertEquals(listOf("2.0.0", "1.0.0"), availableVersions[key])
        assertEquals(listOf("2.0.0", "1.0.0"), model.getValueAt(0, 6))
    }

    /**
     * Stellt sicher, dass "Offer all versions" ältere Versionen sofort anbietet bzw. entfernt.
     */
    fun testApplyVersionVisibilitySettingsTogglesOlderVersions() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val model = findTable(toolWindow.getContent())!!.model as DefaultTableModel
        val (availableVersions, _, knownDependencies) = versionMaps(toolWindow)
        val raw = rawVersionMap(toolWindow)

        val key = "com.example:downgrade-lib"
        knownDependencies[key] = "1.5.0"
        raw[key] = listOf("2.0.0", "1.5.0", "1.0.0")
        model.addRow(arrayOf("com.example", "downgrade-lib", "", "dependency", null, "1.5.0", emptyList<String>()))

        withVersionVisibilitySettings(hideUnstable = false, offerAll = true) {
            toolWindow.applyVersionVisibilitySettings()
        }
        assertEquals(listOf("2.0.0", "1.5.0", "1.0.0"), availableVersions[key])

        withVersionVisibilitySettings(hideUnstable = false, offerAll = false) {
            toolWindow.applyVersionVisibilitySettings()
        }
        assertEquals(listOf("2.0.0", "1.5.0"), availableVersions[key])
        assertEquals(listOf("2.0.0", "1.5.0"), model.getValueAt(0, 6))
    }

    /**
     * Stellt sicher, dass eine Auswahl verworfen wird, die durch die geänderten Einstellungen nicht
     * mehr angeboten wird.
     */
    fun testApplyVersionVisibilitySettingsDropsUnavailableSelection() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val model = findTable(toolWindow.getContent())!!.model as DefaultTableModel
        val (availableVersions, selectedVersions, knownDependencies) = versionMaps(toolWindow)
        val raw = rawVersionMap(toolWindow)

        val key = "com.example:selection-lib"
        knownDependencies[key] = "1.0.0"
        raw[key] = listOf("2.0.0-RC1", "1.0.0")
        availableVersions[key] = raw[key]!!
        selectedVersions[key] = "2.0.0-RC1"
        model.addRow(arrayOf("com.example", "selection-lib", "", "dependency", null, "1.0.0", raw[key]))

        withVersionVisibilitySettings(hideUnstable = true, offerAll = false) {
            toolWindow.applyVersionVisibilitySettings()
        }

        assertEquals(listOf("1.0.0"), availableVersions[key])
        assertFalse("Nicht mehr angebotene Auswahl muss verworfen werden", selectedVersions.containsKey(key))
    }

    /**
     * Stellt sicher, dass die angebotenen Versionen nur bei tatsächlich geänderten Einstellungen
     * neu berechnet werden.
     */
    fun testApplyVersionVisibilitySettingsIfChangedReactsOnlyToChanges() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val (availableVersions, _, knownDependencies) = versionMaps(toolWindow)
        val raw = rawVersionMap(toolWindow)

        val key = "com.example:changed-lib"
        knownDependencies[key] = "1.0.0"
        raw[key] = listOf("2.0.0", "2.0.0-RC1", "1.0.0")

        toolWindow.applyVersionVisibilitySettingsIfChanged()
        assertFalse("Ohne Änderung darf nichts neu berechnet werden", availableVersions.containsKey(key))

        withVersionVisibilitySettings(hideUnstable = true, offerAll = false) {
            toolWindow.applyVersionVisibilitySettingsIfChanged()
        }

        assertEquals(listOf("2.0.0", "1.0.0"), availableVersions[key])
    }

    /**
     * Stellt sicher, dass der zuletzt bekannte Zustand der Anzeigeeinstellungen korrekt ausgelesen wird.
     */
    fun testCurrentVersionVisibilitySettingsReflectsState() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()

        val settings = withVersionVisibilitySettings(hideUnstable = true, offerAll = true) {
            toolWindow.currentVersionVisibilitySettings()
        }

        assertTrue(settings.first)
        assertTrue(settings.third)
    }

    /**
     * Stellt sicher, dass [MavenUpWindowFactory.MyToolWindow.isManagedEntryType] sowohl
     * Managed Dependencies als auch Managed Plugins korrekt identifiziert.
     */
    fun testIsManagedEntryTypeRecognizesManagedEntries() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        val managedDepType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")

        assertTrue(toolWindow.isManagedEntryType(managedDepType))
        assertTrue(toolWindow.isManagedEntryType(MANAGED_PLUGIN))
        assertFalse(toolWindow.isManagedEntryType("dependency"))
        assertFalse(toolWindow.isManagedEntryType("plugin"))
        assertFalse(toolWindow.isManagedEntryType("parent"))
    }

    /**
     * Stellt sicher, dass "Remove from pom.xml" im Kontextmenü der Haupttabelle stets vorhanden ist
     * und für nicht verwaltete Einträge deaktiviert ist.
     */
    @Suppress("OverrideOnly")
    fun testContextMenuRemoveFromPomAlwaysPresentAndDisabledForStandardEntries() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val standardTarget = DependencyContextMenuTarget(
            column = 0,
            groupId = "com.example",
            artifactId = "regular-lib",
            property = "",
            type = "dependency",
            currentVersion = "1.0.0"
        )

        val group = toolWindow.buildContextMenuGroup(standardTarget)
        val removeAction = group.getChildren(null)
            .filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .firstOrNull {
                it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.removeFromPom")
            }

        assertNotNull("Die Aktion 'Remove from pom.xml' muss im Kontextmenü immer vorhanden sein", removeAction)
        val event = com.intellij.testFramework.TestActionEvent.createTestEvent(removeAction!!)
        removeAction.update(event)
        assertFalse("Für normale (nicht verwaltete) Abhängigkeiten muss die Aktion deaktiviert sein", event.presentation.isEnabled)
        assertFalse(toolWindow.isManagedEntryRemovalEnabled("com.example:regular-lib", "dependency"))
    }

    /**
     * Stellt sicher, dass "Remove from pom.xml" für verwaltete Einträge aktiviert ist, nach dem Vormerken
     * deaktiviert wird und bei laufender Aktualisierung ebenfalls deaktiviert ist.
     */
    @Suppress("OverrideOnly")
    fun testContextMenuRemoveFromPomEnabledForManagedEntriesAndDisabledWhenMarkedOrUpdating() {
        val toolWindow = MavenUpWindowFactory().MyToolWindow(project)
        toolWindow.getContent()
        val managedDependencyType = MyMessageBundle.message("toolwindow.MyToolWindow.type.managedDependency")
        val target = DependencyContextMenuTarget(
            column = 0,
            groupId = "com.example",
            artifactId = "managed-lib",
            property = "",
            type = managedDependencyType,
            currentVersion = "1.0.0"
        )

        val group = toolWindow.buildContextMenuGroup(target)
        val removeAction = group.getChildren(null)
            .filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .first {
                it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.removeFromPom")
            }

        val event = com.intellij.testFramework.TestActionEvent.createTestEvent(removeAction)
        removeAction.update(event)
        assertTrue("Für verwaltete Abhängigkeiten muss die Aktion aktiviert sein", event.presentation.isEnabled)
        assertTrue(toolWindow.isManagedEntryRemovalEnabled("com.example:managed-lib", managedDependencyType))

        // Nach Vormerkung zur Entfernung muss die Aktion deaktiviert sein
        toolWindow.markManagedEntryForRemoval("com.example:managed-lib", managedDependencyType, "1.0.0")
        val groupAfterRemoval = toolWindow.buildContextMenuGroup(target)
        val removeActionAfterRemoval = groupAfterRemoval.getChildren(null)
            .filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .first {
                it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.removeFromPom")
            }
        val eventAfterRemoval = com.intellij.testFramework.TestActionEvent.createTestEvent(removeActionAfterRemoval)
        removeActionAfterRemoval.update(eventAfterRemoval)
        assertFalse("Nach Vormerkung zur Entfernung muss die Aktion deaktiviert sein", eventAfterRemoval.presentation.isEnabled)
        assertFalse(toolWindow.isManagedEntryRemovalEnabled("com.example:managed-lib", managedDependencyType))

        // Reset und Prüfung während isUpdating
        toolWindow.resetAllVersionsToCurrent()
        val isUpdatingField = toolWindow.javaClass.getDeclaredField("isUpdating")
            .apply { isAccessible = true }
        isUpdatingField.setBoolean(toolWindow, true)

        val groupWhileUpdating = toolWindow.buildContextMenuGroup(target)
        val removeActionWhileUpdating = groupWhileUpdating.getChildren(null)
            .filterIsInstance<com.intellij.openapi.actionSystem.AnAction>()
            .first {
                it.templatePresentation.text == MyMessageBundle.message("toolwindow.MyToolWindow.contextMenu.removeFromPom")
            }
        val eventWhileUpdating = com.intellij.testFramework.TestActionEvent.createTestEvent(removeActionWhileUpdating)
        removeActionWhileUpdating.update(eventWhileUpdating)
        assertFalse("Während eines laufenden Updates muss die Aktion deaktiviert sein", eventWhileUpdating.presentation.isEnabled)
        assertFalse(toolWindow.isManagedEntryRemovalEnabled("com.example:managed-lib", managedDependencyType))

        isUpdatingField.setBoolean(toolWindow, false)
    }
}
