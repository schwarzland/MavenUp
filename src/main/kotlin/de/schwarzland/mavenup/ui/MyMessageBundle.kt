package de.schwarzland.mavenup.ui

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

private const val BUNDLE = "messages.MyMessageBundle"

/**
 * Zentrale Anlaufstelle für die Lokalisierung (Internationalisierung) von Texten im Plugin.
 *
 * Dieses Objekt kapselt den Zugriff auf das Resource Bundle `messages.MyMessageBundle.properties`
 * unter Verwendung des IntelliJ [DynamicBundle]-Mechanismus.
 *
 * Es wird benötigt, um UI-Texte, Fehlermeldungen und Labels in verschiedenen Sprachen (sofern vorhanden)
 * bereitzustellen und die Typsicherheit für Property-Keys innerhalb der IDE zu gewährleisten.
 */
object MyMessageBundle {
    private val instance = DynamicBundle(MyMessageBundle::class.java, BUNDLE)

    /**
     * Ruft eine lokalisierte Nachricht anhand ihres Schlüssels ab und formatiert sie mit optionalen Parametern.
     *
     * @param key Der Schlüssel der Nachricht im Resource Bundle.
     * @param params Optionale Parameter für Platzhalter in der Nachricht.
     * @return Die lokalisierte und formatierte Nachricht.
     */
    @JvmStatic
    fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any?): @Nls String {
        return instance.getMessage(key, *params)
    }

    /**
     * Erstellt einen Supplier für eine lokalisierte Nachricht, um den Abruf zu verzögern (Lazy Loading).
     *
     * @param key Der Schlüssel der Nachricht im Resource Bundle.
     * @param params Optionale Parameter für Platzhalter in der Nachricht.
     * @return Ein [Supplier], der bei Aufruf die lokalisierte Nachricht liefert.
     */
    @JvmStatic
    fun lazyMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): Supplier<@Nls String> {
        return instance.getLazyMessage(key, *params)
    }
}
