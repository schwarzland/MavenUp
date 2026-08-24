package de.schwarzland.mavenup.ui

import de.schwarzland.mavenup.service.MavenRepositoryBrowser

/**
 * Erstellt die URL zur Repository-Browser-Seite für eine gegebene Abhängigkeit und Version.
 * Verwendet den konfigurierten [MavenRepositoryBrowser]; standard ist [MavenRepositoryBrowser.MVN_REPOSITORY].
 *
 * @param groupId Die GroupId der Abhängigkeit.
 * @param artifactId Die ArtifactId der Abhängigkeit.
 * @param version Die anzuzeigende Version.
 * @param browser Der zu verwendende Repository-Browser.
 * @return Die vollständige URL zur Repository-Browser-Seite.
 */
internal fun buildMavenRepositoryUrl(
    groupId: String,
    artifactId: String,
    version: String,
    browser: MavenRepositoryBrowser = MavenRepositoryBrowser.MVN_REPOSITORY
): String = browser.urlFor(groupId, artifactId, version)
