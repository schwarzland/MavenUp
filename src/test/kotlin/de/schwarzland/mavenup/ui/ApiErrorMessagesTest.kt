package de.schwarzland.mavenup.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.schwarzland.mavenup.model.ApiError
import de.schwarzland.mavenup.model.ApiErrorCause
import de.schwarzland.mavenup.model.ApiErrorSource

/**
 * Testet die Übersetzung strukturierter [ApiError]-Objekte in die im Fehlerbanner angezeigten
 * Meldungen (siehe [apiErrorMessage]).
 */
class ApiErrorMessagesTest : BasePlatformTestCase() {

    /**
     * Ein OSV.dev-Fehlerstatus wird mit seinem HTTP-Statuscode gemeldet.
     */
    fun testOsvHttpStatusMessageNamesTheStatusCode() {
        val message = apiErrorMessage(ApiError(ApiErrorSource.OSV, ApiErrorCause.HttpStatus(503)))

        assertEquals(MyMessageBundle.message("vulnerability.osv.requestFailed.http", 503), message)
        assertTrue(message.contains("503"))
    }

    /**
     * Ein Netzwerkfehler von OSV.dev übernimmt die technische Ursache in die Meldung.
     */
    fun testOsvFailureMessageContainsTheReason() {
        val message = apiErrorMessage(ApiError(ApiErrorSource.OSV, ApiErrorCause.Failure("unknown host")))

        assertEquals(MyMessageBundle.message("vulnerability.osv.requestFailed.exception", "unknown host"), message)
    }

    /**
     * Ein fehlendes OSS-Index-Token verweist auf die Einstellungen.
     */
    fun testOssIndexMissingTokenUsesCredentialsMissingMessage() {
        val message = apiErrorMessage(ApiError(ApiErrorSource.OSS_INDEX, ApiErrorCause.MissingToken))

        assertEquals(MyMessageBundle.message("vulnerability.ossIndex.credentialsMissing"), message)
    }

    /**
     * Ein abgelehntes OSS-Index-Token meldet ein ungültiges oder abgelaufenes Token.
     */
    fun testOssIndexRejectedTokenUsesAuthenticationFailedMessage() {
        val message = apiErrorMessage(ApiError(ApiErrorSource.OSS_INDEX, ApiErrorCause.RejectedToken))

        assertEquals(MyMessageBundle.message("vulnerability.ossIndex.authenticationFailed"), message)
    }

    /**
     * Ein OSS-Index-Fehlerstatus wird mit seinem HTTP-Statuscode gemeldet.
     */
    fun testOssIndexHttpStatusMessageNamesTheStatusCode() {
        val message = apiErrorMessage(ApiError(ApiErrorSource.OSS_INDEX, ApiErrorCause.HttpStatus(500)))

        assertEquals(MyMessageBundle.message("vulnerability.ossIndex.requestFailed.http", 500), message)
    }

    /**
     * Ein Repository-Fehler nennt das betroffene Repository und die technische Ursache.
     */
    fun testRepositoryFailureMessageNamesRepositoryAndReason() {
        val error = ApiError(ApiErrorSource.REPOSITORY, ApiErrorCause.Failure("unknown host"), "private-1")

        val message = apiErrorMessage(error)

        assertEquals(
            MyMessageBundle.message("dependency.repository.requestFailed", "private-1", "unknown host"),
            message
        )
        assertTrue(message.contains("private-1"))
    }

    /**
     * Ein Repository-Fehlerstatus ohne bekannte Bezeichnung bleibt darstellbar.
     */
    fun testRepositoryHttpStatusMessageWithoutLabelStaysReadable() {
        val message = apiErrorMessage(ApiError(ApiErrorSource.REPOSITORY, ApiErrorCause.HttpStatus(500)))

        assertEquals(
            MyMessageBundle.message("dependency.repository.requestFailed", "", "HTTP 500"),
            message
        )
    }
}
