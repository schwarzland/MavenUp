package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.model.ApiError
import de.schwarzland.mavenup.model.ApiErrorCause
import de.schwarzland.mavenup.model.ApiErrorSource

/**
 * Übersetzt einen von OSV.dev gemeldeten Fehler in eine benutzergerechte Meldung.
 *
 * @param cause Die Ursache des Fehlers.
 * @return Die übersetzte Meldung.
 */
private fun osvErrorMessage(cause: ApiErrorCause): String = when (cause) {
    is ApiErrorCause.HttpStatus ->
        MyMessageBundle.message("vulnerability.osv.requestFailed.http", cause.responseCode)

    is ApiErrorCause.Failure ->
        MyMessageBundle.message("vulnerability.osv.requestFailed.exception", cause.reason)

    ApiErrorCause.MissingToken, ApiErrorCause.RejectedToken ->
        MyMessageBundle.message("vulnerability.osv.requestFailed.exception", cause.toString())
}

/**
 * Übersetzt einen vom Sonatype OSS Index gemeldeten Fehler in eine benutzergerechte Meldung.
 *
 * @param cause Die Ursache des Fehlers.
 * @return Die übersetzte Meldung.
 */
private fun ossIndexErrorMessage(cause: ApiErrorCause): String = when (cause) {
    is ApiErrorCause.HttpStatus ->
        MyMessageBundle.message("vulnerability.ossIndex.requestFailed.http", cause.responseCode)

    is ApiErrorCause.Failure ->
        MyMessageBundle.message("vulnerability.ossIndex.requestFailed.exception", cause.reason)

    ApiErrorCause.MissingToken -> MyMessageBundle.message("vulnerability.ossIndex.credentialsMissing")

    ApiErrorCause.RejectedToken -> MyMessageBundle.message("vulnerability.ossIndex.authenticationFailed")
}

/**
 * Übersetzt einen von einem Maven-Repository gemeldeten Fehler in eine benutzergerechte Meldung.
 *
 * @param cause Die Ursache des Fehlers.
 * @param repositoryLabel Die Bezeichnung des betroffenen Repositories, oder `null`.
 * @return Die übersetzte Meldung.
 */
private fun repositoryErrorMessage(cause: ApiErrorCause, repositoryLabel: String?): String {
    val reason = when (cause) {
        is ApiErrorCause.HttpStatus -> "HTTP ${cause.responseCode}"
        is ApiErrorCause.Failure -> cause.reason
        ApiErrorCause.MissingToken, ApiErrorCause.RejectedToken -> cause.toString()
    }
    return MyMessageBundle.message("dependency.repository.requestFailed", repositoryLabel.orEmpty(), reason)
}

/**
 * Übersetzt einen [ApiError] der Service-Schicht in die im Fehlerbanner angezeigte Meldung.
 *
 * Die Zuordnung liegt bewusst in der UI-Schicht, damit die Services keine Oberflächentexte
 * formulieren und ihre Fehler rein strukturiert melden.
 *
 * @param error Der zu übersetzende Fehler.
 * @return Die benutzergerechte Meldung.
 */
internal fun apiErrorMessage(error: ApiError): String = when (error.source) {
    ApiErrorSource.OSV -> osvErrorMessage(error.cause)
    ApiErrorSource.OSS_INDEX -> ossIndexErrorMessage(error.cause)
    ApiErrorSource.REPOSITORY -> repositoryErrorMessage(error.cause, error.repositoryLabel)
}
