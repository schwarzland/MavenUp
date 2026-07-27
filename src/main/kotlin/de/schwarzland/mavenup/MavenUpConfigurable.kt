package de.schwarzland.mavenup

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JTextField
import javax.swing.JComponent

class MavenUpConfigurable(private val project: Project) : Configurable {
    private var jumpOnSingleClickCheckBox: JBCheckBox? = null
    private var selectLatestVersionCheckBox: JBCheckBox? = null
    private var hideUnstableVersionsCheckBox: JBCheckBox? = null
    private var hiddenVersionQualifiersField: JTextField? = null

    override fun getDisplayName(): String = "MavenUp"

    override fun createComponent(): JComponent {
        val settings = MavenUpSettings.getInstance(project)
        return panel {
            row {
                jumpOnSingleClickCheckBox = checkBox(MyMessageBundle.message("settings.jumpOnSingleClick"))
                    .applyToComponent { isSelected = settings.state.jumpOnSingleClick }
                    .component
            }
            row {
                selectLatestVersionCheckBox = checkBox(MyMessageBundle.message("settings.selectLatestVersion"))
                    .applyToComponent { isSelected = settings.state.selectLatestVersion }
                    .component
            }
            row {
                hideUnstableVersionsCheckBox = checkBox(MyMessageBundle.message("settings.hideUnstableVersions"))
                    .applyToComponent { isSelected = settings.state.hideUnstableVersions }
                    .component
            }
            row("      ${MyMessageBundle.message("settings.hiddenVersionQualifiers")}") {
                hiddenVersionQualifiersField = textField()
                    .applyToComponent { text = settings.state.hiddenVersionQualifiers }
                    .component
            }

            hiddenVersionQualifiersField?.isEnabled = settings.state.hideUnstableVersions
            hideUnstableVersionsCheckBox?.addActionListener {
                hiddenVersionQualifiersField?.isEnabled = hideUnstableVersionsCheckBox?.isSelected == true
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = MavenUpSettings.getInstance(project)
        return jumpOnSingleClickCheckBox?.isSelected != settings.state.jumpOnSingleClick ||
                selectLatestVersionCheckBox?.isSelected != settings.state.selectLatestVersion ||
                hideUnstableVersionsCheckBox?.isSelected != settings.state.hideUnstableVersions ||
                hiddenVersionQualifiersField?.text != settings.state.hiddenVersionQualifiers
    }

    override fun apply() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.jumpOnSingleClick = jumpOnSingleClickCheckBox?.isSelected ?: false
        settings.state.selectLatestVersion = selectLatestVersionCheckBox?.isSelected ?: true
        settings.state.hideUnstableVersions = hideUnstableVersionsCheckBox?.isSelected ?: false
        settings.state.hiddenVersionQualifiers = hiddenVersionQualifiersField?.text?.trim().orEmpty()
    }

    override fun reset() {
        val settings = MavenUpSettings.getInstance(project)
        jumpOnSingleClickCheckBox?.isSelected = settings.state.jumpOnSingleClick
        selectLatestVersionCheckBox?.isSelected = settings.state.selectLatestVersion
        hideUnstableVersionsCheckBox?.isSelected = settings.state.hideUnstableVersions
        hiddenVersionQualifiersField?.text = settings.state.hiddenVersionQualifiers
        hiddenVersionQualifiersField?.isEnabled = settings.state.hideUnstableVersions
    }
}
