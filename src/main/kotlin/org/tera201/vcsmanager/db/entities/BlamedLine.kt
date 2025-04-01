package org.tera201.vcsmanager.db.entities

data class BlamedLine(
    val lineNumber: Int,
    val line: String?,
    val author: String?,
    val committer: String?,
    val commit: String?
)