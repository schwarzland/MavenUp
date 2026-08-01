package de.schwarzland.mavenup.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DependencyUpdateTest {
    @Test
    fun testDependencyUpdateStoresValues() {
        val update = DependencyUpdate(
            groupId = "org.example",
            artifactId = "demo-artifact",
            type = "dependency",
            oldVersion = "1.0.0",
            newVersion = "1.1.0"
        )

        assertEquals("org.example", update.groupId)
        assertEquals("demo-artifact", update.artifactId)
        assertEquals("dependency", update.type)
        assertEquals("1.0.0", update.oldVersion)
        assertEquals("1.1.0", update.newVersion)
    }

    @Test
    fun testDependencyUpdateEqualityIsValueBased() {
        val first = DependencyUpdate("org.example", "demo", "dependency", "1.0.0", "1.1.0")
        val second = DependencyUpdate("org.example", "demo", "dependency", "1.0.0", "1.1.0")
        val different = first.copy(newVersion = "1.2.0")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals("1.2.0", different.newVersion)
        assert(first != different)
    }
}
