package de.schwarzland.mavenup.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

interface OssIndexCredentialStore {
    fun store(username: String, token: String)

    fun retrieve(): Credentials?
}

class OssIndexCredentialService(
    private val passwordSafe: PasswordSafe = PasswordSafe.instance
) : OssIndexCredentialStore {
    override fun store(username: String, token: String) {
        val credentials = if (username.isBlank() && token.isBlank()) {
            null
        } else {
            Credentials(username.trim(), token)
        }
        passwordSafe.set(CREDENTIAL_ATTRIBUTES, credentials)
    }

    override fun retrieve(): Credentials? = passwordSafe.get(CREDENTIAL_ATTRIBUTES)

    private companion object {
        val CREDENTIAL_ATTRIBUTES = CredentialAttributes(
            generateServiceName("MavenUp", "Sonatype OSS Index")
        )
    }
}
