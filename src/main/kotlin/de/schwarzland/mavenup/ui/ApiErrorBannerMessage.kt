package de.schwarzland.mavenup.ui

/**
 * Maskiert die HTML-Sonderzeichen einer Fehlermeldung, damit technische Details (z. B. eine
 * Exception-Meldung mit spitzen Klammern) im HTML-basierten Banner unverfälscht erscheinen und
 * nicht als Markup interpretiert werden.
 *
 * @param message Die unmaskierte Meldung.
 * @return Die maskierte Meldung.
 */
internal fun escapeApiErrorBannerHtml(message: String): String =
    message
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Fügt die qualifizierten Fehlermeldungen fehlgeschlagener API-Aufrufe zu einem Banner-Text zusammen.
 *
 * Der Text wird von einem [com.intellij.ui.InlineBanner] in einer `text/html`-Editor-Komponente
 * dargestellt; ein einfaches `\n` erzeugt dort keinen Zeilenumbruch. Mehrere Meldungen werden daher
 * mit `<br>` getrennt und einzeln HTML-maskiert. Leere Meldungen und Dubletten werden verworfen,
 * damit z. B. zwei identische Netzwerkfehler nicht doppelt erscheinen.
 *
 * @param messages Die zusammenzufassenden Meldungen in Anzeigereihenfolge.
 * @return Der Banner-Text oder `null`, wenn keine anzuzeigende Meldung übrig bleibt.
 */
internal fun formatApiErrorBannerMessage(messages: List<String>): String? =
    messages
        .mapNotNull { it.trim().ifEmpty { null } }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString("<br>") { escapeApiErrorBannerHtml(it) }
