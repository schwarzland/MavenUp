package de.schwarzland.mavenup.model

data class DependencyUpdate(
    val groupId: String,
    val artifactId: String,
    val type: String,
    val oldVersion: String,
    val newVersion: String
)
