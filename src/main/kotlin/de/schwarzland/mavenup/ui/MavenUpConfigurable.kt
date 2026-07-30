package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.MyMessageBundle
import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.service.OssIndexCredentialService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField

internal const val OSS_INDEX_ACCOUNT_URL = "https://ossindex.sonatype.org"

class MavenUpConfigurable(private val project: Project) : Configurable {
    private val credentialService = OssIndexCredentialService()
    private var jumpOnSingleClickCheckBox: JBCheckBox? = null
    private var selectLatestVersionCheckBox: JBCheckBox? = null
    private var hideUnstableVersionsCheckBox: JBCheckBox? = null
    private var hiddenVersionQualifiersLabel: JLabel? = null
    private var hiddenVersionQualifiersField: JTextField? = null
    private var checkTransitiveDependenciesCheckBox: JBCheckBox? = null
    private var ossIndexEnabledCheckBox: JBCheckBox? = null
    private var ossIndexUsernameLabel: JLabel? = null
    private var ossIndexUsernameField: JTextField? = null
    private var ossIndexTokenLabel: JLabel? = null
    private var ossIndexTokenField: JPasswordField? = null

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
            row {
                hiddenVersionQualifiersLabel = label(
                    "       ${MyMessageBundle.message("settings.hiddenVersionQualifiers")}"
                ).component
                hiddenVersionQualifiersField = textField()
                    .applyToComponent {
                        text = settings.state.hiddenVersionQualifiers
                        columns = 40
                    }
                    .component
            }
            group(MyMessageBundle.message("settings.vulnerability.group")) {
                row {
                    checkTransitiveDependenciesCheckBox =
                        checkBox(MyMessageBundle.message("settings.checkTransitiveDependencies"))
                            .applyToComponent { isSelected = settings.state.checkTransitiveDependencies }
                            .component
                }
                row {
                    ossIndexEnabledCheckBox = checkBox(MyMessageBundle.message("settings.ossIndex.enabled"))
                        .applyToComponent { isSelected = settings.state.ossIndexEnabled }
                        .component
                }
                row {
                    ossIndexUsernameLabel = label(MyMessageBundle.message("settings.ossIndex.username")).component
                    ossIndexUsernameField = textField()
                        .applyToComponent {
                            text = settings.state.ossIndexUsername
                            columns = 30
                        }
                        .component
                }
                row {
                    ossIndexTokenLabel = label(MyMessageBundle.message("settings.ossIndex.token")).component
                    ossIndexTokenField = cell(JPasswordField(30))
                        .applyToComponent {
                            text = credentialService.retrieve()?.getPasswordAsString().orEmpty()
                        }
                        .component
                }
                row {
                    comment(MyMessageBundle.message("settings.ossIndex.hint"))
                }
                row {
                    browserLink(
                        MyMessageBundle.message("settings.ossIndex.accountLink"),
                        OSS_INDEX_ACCOUNT_URL
                    )
                }
            }

            updateHiddenQualifierControlsEnabled(settings.state.hideUnstableVersions)
            hideUnstableVersionsCheckBox?.addActionListener {
                updateHiddenQualifierControlsEnabled(hideUnstableVersionsCheckBox?.isSelected == true)
            }
            updateOssIndexControlsEnabled(settings.state.ossIndexEnabled)
            ossIndexEnabledCheckBox?.addActionListener {
                updateOssIndexControlsEnabled(ossIndexEnabledCheckBox?.isSelected == true)
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = MavenUpSettings.getInstance(project)
        return jumpOnSingleClickCheckBox?.isSelected != settings.state.jumpOnSingleClick ||
                selectLatestVersionCheckBox?.isSelected != settings.state.selectLatestVersion ||
                hideUnstableVersionsCheckBox?.isSelected != settings.state.hideUnstableVersions ||
                hiddenVersionQualifiersField?.text != settings.state.hiddenVersionQualifiers ||
                checkTransitiveDependenciesCheckBox?.isSelected != settings.state.checkTransitiveDependencies ||
                ossIndexEnabledCheckBox?.isSelected != settings.state.ossIndexEnabled ||
                ossIndexUsernameField?.text?.trim() != settings.state.ossIndexUsername ||
                currentToken() != credentialService.retrieve()?.getPasswordAsString().orEmpty()
    }

    override fun apply() {
        val settings = MavenUpSettings.getInstance(project)
        settings.state.jumpOnSingleClick = jumpOnSingleClickCheckBox?.isSelected ?: false
        settings.state.selectLatestVersion = selectLatestVersionCheckBox?.isSelected ?: true
        settings.state.hideUnstableVersions = hideUnstableVersionsCheckBox?.isSelected ?: false
        settings.state.hiddenVersionQualifiers = hiddenVersionQualifiersField?.text?.trim().orEmpty()
        settings.state.checkTransitiveDependencies = checkTransitiveDependenciesCheckBox?.isSelected ?: true
        settings.state.ossIndexEnabled = ossIndexEnabledCheckBox?.isSelected ?: false
        settings.state.ossIndexUsername = ossIndexUsernameField?.text?.trim().orEmpty()
        credentialService.store(settings.state.ossIndexUsername, currentToken())
    }

    override fun reset() {
        val settings = MavenUpSettings.getInstance(project)
        jumpOnSingleClickCheckBox?.isSelected = settings.state.jumpOnSingleClick
        selectLatestVersionCheckBox?.isSelected = settings.state.selectLatestVersion
        hideUnstableVersionsCheckBox?.isSelected = settings.state.hideUnstableVersions
        hiddenVersionQualifiersField?.text = settings.state.hiddenVersionQualifiers
        checkTransitiveDependenciesCheckBox?.isSelected = settings.state.checkTransitiveDependencies
        ossIndexEnabledCheckBox?.isSelected = settings.state.ossIndexEnabled
        ossIndexUsernameField?.text = settings.state.ossIndexUsername
        ossIndexTokenField?.text = credentialService.retrieve()?.getPasswordAsString().orEmpty()
        updateHiddenQualifierControlsEnabled(settings.state.hideUnstableVersions)
        updateOssIndexControlsEnabled(settings.state.ossIndexEnabled)
    }

    private fun updateHiddenQualifierControlsEnabled(enabled: Boolean) {
        hiddenVersionQualifiersLabel?.isEnabled = enabled
        hiddenVersionQualifiersField?.isEnabled = enabled
    }

    private fun updateOssIndexControlsEnabled(enabled: Boolean) {
        ossIndexUsernameLabel?.isEnabled = enabled
        ossIndexUsernameField?.isEnabled = enabled
        ossIndexTokenLabel?.isEnabled = enabled
        ossIndexTokenField?.isEnabled = enabled
    }

    private fun currentToken(): String = ossIndexTokenField?.password?.concatToString().orEmpty()
}
