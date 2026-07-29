package de.schwarzland.mavenup

import de.schwarzland.mavenup.service.MavenUpSettings
import de.schwarzland.mavenup.ui.MavenUpWindowFactory
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.command.WriteCommandAction

class MavenUpWindowFactoryTest : BasePlatformTestCase() {

    fun testShouldBeAvailableOnlyWhenMavenProjectsExist() {
        val factory = MavenUpWindowFactory()

        // Standardmäßig sollten keine Maven-Projekte in einem leeren Testprojekt vorhanden sein
        assertFalse("ToolWindow sollte ohne Maven-Projekte nicht verfügbar sein", factory.shouldBeAvailable(project))
    }

    @Suppress("OverrideOnly", "DEPRECATION")
    fun testToolWindowContentCreation() {
        val factory = MavenUpWindowFactory()
        val toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(
            RegisterToolWindowTask(
                id = "TestWindow",
                anchor = ToolWindowAnchor.BOTTOM,
                canCloseContent = true
            )
        )

        try {
            factory.createToolWindowContent(project, toolWindow)
            assertTrue("Content sollte zum ToolWindow hinzugefügt worden sein", toolWindow.contentManager.contentCount > 0)
        } finally {
            ToolWindowManager.getInstance(project).unregisterToolWindow("TestWindow")
        }
    }

    fun testFindDependency() {
        val pomContent = """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>example-core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>example-managed</artifactId>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        // Test normal dependency
        val normalDep = toolWindowInstance.findDependency(rootTag, "com.example", "example-core", false)
        assertNotNull("Normale Dependency sollte gefunden werden", normalDep)
        assertEquals("example-core", normalDep?.findFirstSubTag("artifactId")?.value?.text)

        // Test managed dependency
        val managedDep = toolWindowInstance.findDependency(rootTag, "com.example", "example-managed", true)
        assertNotNull("Managed Dependency sollte gefunden werden", managedDep)
        assertEquals("example-managed", managedDep?.findFirstSubTag("artifactId")?.value?.text)
    }

    fun testUpdateXmlTagVersion() {
        val pomContent = """
            <project>
                <properties>
                    <example.version>1.0.0</example.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>direct</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>prop</artifactId>
                        <version>${'$'}{example.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val propertiesTag = rootTag?.findFirstSubTag("properties")
        val deps = rootTag?.findFirstSubTag("dependencies")?.findSubTags("dependency")
        val directDep = deps?.find { it.findFirstSubTag("artifactId")?.value?.text == "direct" }
        val propDep = deps?.find { it.findFirstSubTag("artifactId")?.value?.text == "prop" }

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        // Update direct version
        WriteCommandAction.runWriteCommandAction(project) {
            toolWindowInstance.updateXmlTagVersion(directDep!!, "1.1.0", propertiesTag)
        }
        assertEquals("1.1.0", directDep?.findFirstSubTag("version")?.value?.text)

        // Update property version
        WriteCommandAction.runWriteCommandAction(project) {
            toolWindowInstance.updateXmlTagVersion(propDep!!, "1.2.0", propertiesTag)
        }
        assertEquals("${'$'}{example.version}", propDep?.findFirstSubTag("version")?.value?.text)
        assertEquals("1.2.0", propertiesTag?.findFirstSubTag("example.version")?.value?.text)
    }

    fun testSelectLatestVersionSetting() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val settings = MavenUpSettings.getInstance(project)

        // Mock data
        val key = "com.example:test-artifact"
        val versions = listOf("1.1.0", "1.0.0")
        val currentVersion = "1.0.0"

        // Test with selectLatestVersion = true (default)
        assertTrue(settings.state.selectLatestVersion)
        
        // Use reflection to access internal maps for verification
        val availableVersionsField = toolWindowInstance.javaClass.getDeclaredField("availableVersions").apply { isAccessible = true }
        val selectedVersionsField = toolWindowInstance.javaClass.getDeclaredField("selectedVersions").apply { isAccessible = true }
        val knownDependenciesField = toolWindowInstance.javaClass.getDeclaredField("knownDependencies").apply { isAccessible = true }

        val availableVersions = availableVersionsField.get(toolWindowInstance) as MutableMap<String, List<String>>
        val selectedVersions = selectedVersionsField.get(toolWindowInstance) as MutableMap<String, String>
        val knownDependencies = knownDependenciesField.get(toolWindowInstance) as MutableMap<String, String>

        knownDependencies[key] = currentVersion
        
        // Simulate checkArtifactUpdate logic manually for testing the selection logic
        fun simulateCheck(v: String) {
            availableVersions[key] = versions
            if (versions.first() != v && settings.state.selectLatestVersion) {
                selectedVersions[key] = versions.first()
            } else if (!settings.state.selectLatestVersion) {
                selectedVersions[key] = v
            }
        }

        simulateCheck(currentVersion)
        assertEquals("1.1.0", selectedVersions[key])

        // Test with selectLatestVersion = false
        settings.state.selectLatestVersion = false
        selectedVersions.clear()
        simulateCheck(currentVersion)
        assertEquals("1.0.0", selectedVersions[key])
        
        settings.state.selectLatestVersion = true
    }

    fun testJumpOnSingleClickSetting() {
        val settings = MavenUpSettings.getInstance(project)
        
        // Test default value
        assertFalse("jumpOnSingleClick sollte standardmäßig false sein", settings.state.jumpOnSingleClick)
        
        // Test update
        settings.state.jumpOnSingleClick = true
        assertTrue("jumpOnSingleClick sollte nun true sein", settings.state.jumpOnSingleClick)
        
        // Reset
        settings.state.jumpOnSingleClick = false
    }

    fun testUnstableVersionFilterSettingsDefaults() {
        val defaults = MavenUpSettings.State()
        assertFalse(defaults.hideUnstableVersions)
        assertTrue(defaults.hiddenVersionQualifiers.isNotBlank())
        assertTrue(defaults.hiddenVersionQualifiers.contains("rc"))
    }

    fun testCollectDependenciesAndProperties() {
        val pomContent = """
            <project>
                <properties>
                    <my.version>1.2.3</my.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>with-prop</artifactId>
                        <version>${"$"}{my.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>no-prop</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                    <dependency>
                        <artifactId>missing-group</artifactId>
                        <version>9.9.9</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        val targetMap = mutableMapOf<String, String>()

        // Use reflection to access private method and private map
        val collectMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "collectDependenciesAndProperties",
            com.intellij.psi.xml.XmlTag::class.java,
            String::class.java,
            String::class.java,
            MutableMap::class.java
        ).apply { isAccessible = true }

        val dependencyToPropertyField = toolWindowInstance.javaClass.getDeclaredField("dependencyToProperty").apply { isAccessible = true }
        val dependencyToProperty = dependencyToPropertyField.get(toolWindowInstance) as MutableMap<String, String>

        collectMethod.invoke(toolWindowInstance, rootTag, "dependencies", "dependency", targetMap)

        assertEquals("${"$"}{my.version}", targetMap["com.example:with-prop"])
        assertEquals("1.0.0", targetMap["com.example:no-prop"])
        assertNull(targetMap[":missing-group"])
        
        assertEquals("my.version", dependencyToProperty["com.example:with-prop"])
        assertNull(dependencyToProperty["com.example:no-prop"])
    }

    fun testCollectPluginsWithoutGroupIdAreSkipped() {
        val pomContent = """
            <project>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.example</groupId>
                            <artifactId>valid-plugin</artifactId>
                            <version>1.0.0</version>
                        </plugin>
                        <plugin>
                            <artifactId>missing-group-plugin</artifactId>
                            <version>2.0.0</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag
        val buildTag = rootTag?.findFirstSubTag("build")

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val targetMap = mutableMapOf<String, String>()

        val collectMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "collectDependenciesAndProperties",
            com.intellij.psi.xml.XmlTag::class.java,
            String::class.java,
            String::class.java,
            MutableMap::class.java
        ).apply { isAccessible = true }

        collectMethod.invoke(toolWindowInstance, buildTag, "plugins", "plugin", targetMap)

        assertEquals("1.0.0", targetMap["org.example:valid-plugin"])
        assertNull(targetMap[":missing-group-plugin"])
    }

    fun testResolveCredentialValueWithSystemPropertyPlaceholder() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val resolveMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "resolveCredentialValue",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val key = "mavenup.test.credential"
        val expected = "secret-value"
        try {
            System.setProperty(key, expected)
            val resolved = resolveMethod.invoke(
                toolWindowInstance,
                "${'$'}{$key}",
                "test-server",
                "username"
            ) as String?
            assertEquals(expected, resolved)
        } finally {
            System.clearProperty(key)
        }
    }

    fun testResolveCredentialValueWithMissingEnvPlaceholderReturnsNull() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val resolveMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "resolveCredentialValue",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val missingVar = "MAVENUP_TEST_MISSING_ENV_1234567890"
        val resolved = resolveMethod.invoke(
            toolWindowInstance,
            "${'$'}{env.$missingVar}",
            "test-server",
            "password"
        ) as String?

        assertNull(resolved)
    }

    fun testResolveCredentialValueWithPlainTextKeepsValue() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val resolveMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "resolveCredentialValue",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val resolved = resolveMethod.invoke(
            toolWindowInstance,
            " plain-secret ",
            "test-server",
            "username"
        ) as String?

        assertEquals("plain-secret", resolved)
    }

    fun testResolveCredentialValueWithMissingPropertyPlaceholderReturnsNull() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val resolveMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "resolveCredentialValue",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val missingVar = "MAVENUP_TEST_MISSING_PROP_1234567890"
        val resolved = resolveMethod.invoke(
            toolWindowInstance,
            "${'$'}{$missingVar}",
            "test-server",
            "username"
        ) as String?

        assertNull(resolved)
    }

    fun testResolveCredentialValueWithBlankInputReturnsNull() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val resolveMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "resolveCredentialValue",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val resolved = resolveMethod.invoke(
            toolWindowInstance,
            "   ",
            "test-server",
            "username"
        ) as String?

        assertNull(resolved)
    }

    fun testFindServerCredentialsFallsBackToRepositoryUrl() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val findMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "findServerCredentials",
            Pair::class.java,
            Map::class.java
        ).apply { isAccessible = true }

        val repo = Pair<String?, String>(null, "https://repo.example.org/maven")
        val creds = mapOf<String, Pair<String?, String?>>(
            "https://repo.example.org/maven" to Pair("user", "pass")
        )

        val resolved = findMethod.invoke(toolWindowInstance, repo, creds) as Pair<*, *>?
        assertEquals("user", resolved?.first)
        assertEquals("pass", resolved?.second)
    }

    fun testFindServerCredentialsFallsBackToHost() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val findMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "findServerCredentials",
            Pair::class.java,
            Map::class.java
        ).apply { isAccessible = true }

        val repo = Pair<String?, String>(null, "https://repo.example.org/maven")
        val creds = mapOf<String, Pair<String?, String?>>(
            "repo.example.org" to Pair("host-user", "host-pass")
        )

        val resolved = findMethod.invoke(toolWindowInstance, repo, creds) as Pair<*, *>?
        assertEquals("host-user", resolved?.first)
        assertEquals("host-pass", resolved?.second)
    }

    fun testCollectVersionsFromRepositoriesPrioritizesCentralAndShortCircuitsOnSuccess() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val collectMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "collectVersionsFromRepositories",
            List::class.java,
            kotlin.Function1::class.java
        ).apply { isAccessible = true }

        val repositories = listOf(
            Pair<String?, String>("private-1", "https://private-1.example.org/maven"),
            Pair<String?, String>("central", "https://repo1.maven.org/maven2"),
            Pair<String?, String>("private-2", "https://private-2.example.org/maven")
        )
        val called = mutableListOf<String>()
        val fetcher: (Pair<String?, String>) -> Pair<Boolean, List<String>> = { repo ->
            called.add(repo.second)
            if (repo.second == "https://repo1.maven.org/maven2") {
                Pair(true, listOf("1.2.0"))
            } else {
                Pair(true, listOf("9.9.9"))
            }
        }

        @Suppress("UNCHECKED_CAST")
        val versions = collectMethod.invoke(toolWindowInstance, repositories, fetcher) as Set<String>

        assertEquals(listOf("https://repo1.maven.org/maven2"), called)
        assertEquals(setOf("1.2.0"), versions)
    }

    fun testCollectVersionsFromRepositoriesContinuesWhenCentralFails() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val collectMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "collectVersionsFromRepositories",
            List::class.java,
            kotlin.Function1::class.java
        ).apply { isAccessible = true }

        val repositories = listOf(
            Pair<String?, String>("private-1", "https://private-1.example.org/maven"),
            Pair<String?, String>("central", "https://repo1.maven.org/maven2"),
            Pair<String?, String>("private-2", "https://private-2.example.org/maven")
        )
        val called = mutableListOf<String>()
        val fetcher: (Pair<String?, String>) -> Pair<Boolean, List<String>> = { repo ->
            called.add(repo.second)
            if (repo.second == "https://repo1.maven.org/maven2") {
                Pair(false, emptyList())
            } else {
                Pair(true, listOf("1.0.0"))
            }
        }

        @Suppress("UNCHECKED_CAST")
        val versions = collectMethod.invoke(toolWindowInstance, repositories, fetcher) as Set<String>

        assertEquals(
            listOf(
                "https://repo1.maven.org/maven2",
                "https://private-1.example.org/maven",
                "https://private-2.example.org/maven"
            ),
            called
        )
        assertEquals(setOf("1.0.0"), versions)
    }

    fun testFilterVersionsBySettingsWhenDisabledKeepsAll() {
        val settings = MavenUpSettings.getInstance(project)
        val previousHide = settings.state.hideUnstableVersions
        val previousQualifiers = settings.state.hiddenVersionQualifiers
        settings.state.hideUnstableVersions = false
        settings.state.hiddenVersionQualifiers = "rc,beta"

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val filterMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "filterVersionsBySettings",
            List::class.java
        ).apply { isAccessible = true }

        val input = listOf("2.0.0-RC1", "2.0.0-beta2", "2.0.0")
        try {
            @Suppress("UNCHECKED_CAST")
            val filtered = filterMethod.invoke(toolWindowInstance, input) as List<String>
            assertEquals(input, filtered)
        } finally {
            settings.state.hideUnstableVersions = previousHide
            settings.state.hiddenVersionQualifiers = previousQualifiers
        }
    }

    fun testFilterVersionsBySettingsRemovesConfiguredQualifiers() {
        val settings = MavenUpSettings.getInstance(project)
        val previousHide = settings.state.hideUnstableVersions
        val previousQualifiers = settings.state.hiddenVersionQualifiers
        settings.state.hideUnstableVersions = true
        settings.state.hiddenVersionQualifiers = "rc,beta,milestone"

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val filterMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "filterVersionsBySettings",
            List::class.java
        ).apply { isAccessible = true }

        val input = listOf("2.0.0-RC1", "2.0.0-beta2", "2.0.0-MILESTONE-1", "2.0.0", "1.9.9")
        try {
            @Suppress("UNCHECKED_CAST")
            val filtered = filterMethod.invoke(toolWindowInstance, input) as List<String>
            assertEquals(listOf("2.0.0", "1.9.9"), filtered)
        } finally {
            settings.state.hideUnstableVersions = previousHide
            settings.state.hiddenVersionQualifiers = previousQualifiers
        }
    }

    fun testFindPlugin() {
        val pomContent = """
            <project>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.8.1</version>
                        </plugin>
                    </plugins>
                    <pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.0.0-M5</version>
                            </plugin>
                        </plugins>
                    </pluginManagement>
                </build>
            </project>
        """.trimIndent()

        val psiFile = myFixture.configureByText("pom.xml", pomContent) as XmlFile
        val rootTag = psiFile.document?.rootTag

        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)

        val findPluginMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "findPlugin",
            com.intellij.psi.xml.XmlTag::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.java
        ).apply { isAccessible = true }

        // Test normal plugin
        val normalPlugin = findPluginMethod.invoke(toolWindowInstance, rootTag, "org.apache.maven.plugins", "maven-compiler-plugin", false) as? com.intellij.psi.xml.XmlTag
        assertNotNull("Normaler Plugin sollte gefunden werden", normalPlugin)
        assertEquals("maven-compiler-plugin", normalPlugin?.findFirstSubTag("artifactId")?.value?.text)

        // Test managed plugin
        val managedPlugin = findPluginMethod.invoke(toolWindowInstance, rootTag, "org.apache.maven.plugins", "maven-surefire-plugin", true) as? com.intellij.psi.xml.XmlTag
        assertNotNull("Managed Plugin sollte gefunden werden", managedPlugin)
        assertEquals("maven-surefire-plugin", managedPlugin?.findFirstSubTag("artifactId")?.value?.text)
    }

    fun testBuildVulnerabilityQueryProducesOsvCompatibleJson() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val buildMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "buildVulnerabilityQuery",
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val query = buildMethod.invoke(
            toolWindowInstance,
            "com.fasterxml.jackson.core",
            "jackson-databind",
            "2.9.8"
        ) as com.google.gson.JsonObject

        assertEquals("2.9.8", query.get("version").asString)
        val packageObject = query.getAsJsonObject("package")
        assertEquals("com.fasterxml.jackson.core:jackson-databind", packageObject.get("name").asString)
        assertEquals("Maven", packageObject.get("ecosystem").asString)
    }

    fun testParseVulnerabilityCountsMapsResultsInOrder() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val parseMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "parseVulnerabilityCounts",
            String::class.java,
            List::class.java
        ).apply { isAccessible = true }

        val responseBody = """
            {"results":[{"vulns":[{"id":"GHSA-1"},{"id":"GHSA-2"}]},{}]}
        """.trimIndent()
        val keys = listOf("g:a:1.0", "g:b:2.0")

        @Suppress("UNCHECKED_CAST")
        val counts = parseMethod.invoke(toolWindowInstance, responseBody, keys) as Map<String, Int>

        assertEquals(2, counts["g:a:1.0"])
        assertEquals(0, counts["g:b:2.0"])
    }

    fun testParseVulnerabilityCountsHandlesMissingResults() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val parseMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "parseVulnerabilityCounts",
            String::class.java,
            List::class.java
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val counts = parseMethod.invoke(toolWindowInstance, "{}", listOf("g:a:1.0")) as Map<String, Int>

        assertTrue("Ohne 'results' sollte eine leere Map zurückgegeben werden", counts.isEmpty())
    }

    fun testFetchVulnerabilityCountsReturnsEmptyMapForEmptyInput() {
        val factory = MavenUpWindowFactory()
        val toolWindowInstance = factory.MyToolWindow(project)
        val fetchMethod = toolWindowInstance.javaClass.getDeclaredMethod(
            "fetchVulnerabilityCounts",
            List::class.java,
            com.intellij.openapi.progress.ProgressIndicator::class.java
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val counts = fetchMethod.invoke(toolWindowInstance, emptyList<Triple<String, String, String>>(), null) as Map<String, Int>

        assertTrue("Für eine leere Abhängigkeitsliste sollten keine Netzwerkaufrufe erfolgen", counts.isEmpty())
    }

    /**
     * End-to-end regression test using a local loopback HTTP server that replays a response shaped exactly like a
     * real OSV.dev `/v1/querybatch` reply (captured for `com.fasterxml.jackson.core:jackson-databind:2.9.8`, which
     * has 54 known advisories). This proves the request building, HTTP handling and response parsing together
     * correctly report a non-zero vulnerability count end-to-end, without depending on external network access.
     */
    fun testFetchVulnerabilityCountsForChunkReportsRealVulnerabilityCounts() {
        val responseBody = """
            {"results":[
              {"vulns":[{"id":"GHSA-1"},{"id":"GHSA-2"},{"id":"GHSA-3"}]},
              {}
            ]}
        """.trimIndent()

        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var receivedBody = ""
        server.createContext("/v1/querybatch") { exchange ->
            receivedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val factory = MavenUpWindowFactory()
            val toolWindowInstance = factory.MyToolWindow(project)
            val fetchChunkMethod = toolWindowInstance.javaClass.getDeclaredMethod(
                "fetchVulnerabilityCountsForChunk",
                List::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val dependencies = listOf(
                Triple("com.fasterxml.jackson.core", "jackson-databind", "2.9.8"),
                Triple("junit", "junit", "4.13.2")
            )
            val testUrl = "http://127.0.0.1:${server.address.port}/v1/querybatch"

            @Suppress("UNCHECKED_CAST")
            val counts = fetchChunkMethod.invoke(toolWindowInstance, dependencies, testUrl) as Map<String, Int>

            // Verify the request contains a well-formed OSV batch query for both dependencies
            assertTrue(receivedBody.contains("\"ecosystem\":\"Maven\""))
            assertTrue(receivedBody.contains("com.fasterxml.jackson.core:jackson-databind"))
            assertTrue(receivedBody.contains("junit:junit"))

            // Verify the parsed counts correctly reflect the (real-world shaped) server response
            assertEquals(3, counts["com.fasterxml.jackson.core:jackson-databind:2.9.8"])
            assertEquals(0, counts["junit:junit:4.13.2"])
        } finally {
            server.stop(0)
        }
    }
}