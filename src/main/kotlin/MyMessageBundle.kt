package de.schwarzland

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

/**
 * Kurz zusammengefasst
 * Diese Datei ist ein kleiner Utility-Wrapper für lokalisierte Texte im IntelliJ-Plugin.
 * Sie bietet:
 * Zugriff auf messages/MyMessageBundle.properties
 * direkte Nachrichten über message(...)
 * verzögerte Nachrichten über lazyMessage(...)
 * IDE-Unterstützung für gültige Property-Keys
 * Java-Kompatibilität durch @JvmStatic
 * Im Plugin-Code wird dadurch vermieden, überall direkt mit DynamicBundle zu arbeiten. Stattdessen ruft man zentral MyMessageBundle.message("some.key") auf.
 */

private const val BUNDLE = "messages.MyMessageBundle"

internal object MyMessageBundle {
    private val instance = DynamicBundle(MyMessageBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any?): @Nls String {
        return instance.getMessage(key, *params)
    }

    @JvmStatic
    fun lazyMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): Supplier<@Nls String> {
        return instance.getLazyMessage(key, *params)
    }
}
