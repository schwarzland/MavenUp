package de.schwarzland.mavenup

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class MavenUpConfigurable(private val project: Project) : Configurable {
    private var jumpOnSingleClickCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "MavenUp"

    override fun createComponent(): JComponent {
        val settings = MavenUpSettings.getInstance(project)
        return panel {
            row {
                jumpOnSingleClickCheckBox = checkBox(MyMessageBundle.message("settings.jumpOnSingleClick"))
                    .applyToComponent { isSelected = settings.state.jumpOnSingleClick }
                    .component
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = MavenUpSettings.getInstance(project)
        return jumpOnSingleClickCheckBox?.isSelected != settings.state.jumpOnSingleClick
    }

    override fun apply() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.jumpOnSingleClick = jumpOnSingleClickCheckBox?.isSelected ?: false
    }

    override fun reset() {
        val settings = MavenUpSettings.getInstance(project)
        jumpOnSingleClickCheckBox?.isSelected = settings.state.jumpOnSingleClick
    }
}
