package de.schwarzland.mavenup.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Schnittstelle für das Speichern und Abrufen von Zugangsdaten für den Sonatype OSS Index.
 */
interface OssIndexCredentialStore {
    /**
     * Speichert den Benutzernamen und das API-Token sicher.
     * Wenn beide Werte leer sind, werden die vorhandenen Zugangsdaten gelöscht.
     */
    fun store(username: String, token: String)

    /**
     * Ruft die gespeicherten Zugangsdaten ab.
     * @return Die [Credentials] oder null, wenn keine gespeichert sind.
     */
    fun retrieve(): Credentials?
}

/**
 * Service zur sicheren Verwaltung von OSS-Index-Zugangsdaten unter Verwendung der IntelliJ [PasswordSafe]-API.
 *
 * Diese Klasse wird benötigt, um sensible API-Token verschlüsselt im System-Schlüsselbund
 * oder in der passwortgeschützten Datenbank der IDE zu hinterlegen.
 */
class OssIndexCredentialService(
    private val passwordSafe: PasswordSafe = PasswordSafe.instance
) : OssIndexCredentialStore {
    /**
     * Implementiert das sichere Speichern der Zugangsdaten im [PasswordSafe].
     */
    override fun store(username: String, token: String) {
        val credentials = if (username.isBlank() && token.isBlank()) {
            null
        } else {
            Credentials(username.trim(), token)
        }
        passwordSafe[CREDENTIAL_ATTRIBUTES] = credentials
    }

    /**
     * Implementiert das Abrufen der Zugangsdaten aus dem [PasswordSafe].
     */
    override fun retrieve(): Credentials? = passwordSafe[CREDENTIAL_ATTRIBUTES]

    private companion object {
        /**
         * Attribute zur Identifizierung der gespeicherten Zugangsdaten im PasswordSafe.
         */
        val CREDENTIAL_ATTRIBUTES = CredentialAttributes(
            generateServiceName("MavenUp", "Sonatype OSS Index")
        )
    }
}
