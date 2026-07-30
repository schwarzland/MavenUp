package de.schwarzland.mavenup.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

class OssIndexCredentialService(
    private val passwordSafe: PasswordSafe = PasswordSafe.instance
) {
    fun store(username: String, token: String) {
        val credentials = if (username.isBlank() && token.isBlank()) {
            null
        } else {
            Credentials(username.trim(), token)
        }
        passwordSafe.set(CREDENTIAL_ATTRIBUTES, credentials)
    }

    fun retrieve(): Credentials? = passwordSafe.get(CREDENTIAL_ATTRIBUTES)

    fun clear() {
        passwordSafe.set(CREDENTIAL_ATTRIBUTES, null)
    }

    private companion object {
        val CREDENTIAL_ATTRIBUTES = CredentialAttributes(
            generateServiceName("MavenUp", "Sonatype OSS Index")
        )
    }
}
