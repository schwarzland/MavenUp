package de.schwarzland.mavenup.model

/**
 * Die Herkunft eines fehlgeschlagenen API-Aufrufs.
 */
enum class ApiErrorSource {
    /** Die Schwachstellendatenbank OSV.dev. */
    OSV,

    /** Der Sonatype OSS Index. */
    OSS_INDEX,

    /** Ein Maven-Repository (Maven Central oder ein konfiguriertes privates Repository). */
    REPOSITORY
}

/**
 * Die Ursache eines fehlgeschlagenen API-Aufrufs.
 *
 * Die Ursache wird bewusst strukturiert (statt als fertiger Meldungstext) transportiert, damit die
 * Service-Schicht keine Oberflächentexte formulieren muss und die Benutzeroberfläche die Übersetzung
 * übernimmt (siehe `de.schwarzland.mavenup.ui.apiErrorMessage`).
 */
sealed interface ApiErrorCause {

    /**
     * Der Dienst hat mit einem Fehlerstatus geantwortet.
     *
     * @property responseCode Der zurückgelieferte HTTP-Statuscode.
     */
    data class HttpStatus(val responseCode: Int) : ApiErrorCause

    /**
     * Der Aufruf ist ohne verwertbare HTTP-Antwort fehlgeschlagen, z. B. durch einen unauflösbaren
     * Host bei falsch konfigurierter URI oder einen Zeitüberlauf.
     *
     * @property reason Die technische Beschreibung des Fehlers.
     */
    data class Failure(val reason: String) : ApiErrorCause

    /** Es ist kein API-Token hinterlegt, sodass der Aufruf übersprungen wurde. */
    data object MissingToken : ApiErrorCause

    /** Das hinterlegte API-Token wurde abgelehnt (ungültig oder abgelaufen). */
    data object RejectedToken : ApiErrorCause
}

/**
 * Beschreibt einen fehlgeschlagenen Aufruf eines externen Dienstes.
 *
 * @property source Die Herkunft des Fehlers.
 * @property cause Die Ursache des Fehlers.
 * @property repositoryLabel Die Bezeichnung (ID oder URL) des betroffenen Repositories bei
 *   [ApiErrorSource.REPOSITORY], sonst `null`.
 */
data class ApiError(
    val source: ApiErrorSource,
    val cause: ApiErrorCause,
    val repositoryLabel: String? = null
) {

    /**
     * `true`, wenn der Fehler auf ein fehlendes oder abgelehntes API-Token zurückgeht und damit über
     * die Plugin-Einstellungen behebbar ist.
     */
    val isTokenError: Boolean
        get() = cause == ApiErrorCause.MissingToken || cause == ApiErrorCause.RejectedToken
}
