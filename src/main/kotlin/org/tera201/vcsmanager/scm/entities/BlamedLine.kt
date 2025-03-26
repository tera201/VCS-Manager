package org.tera201.vcsmanager.scm.entities

data class BlamedLine(
    val lineNumber: Int,
    val line: String?,
    val author: String?,
    val committer: String?,
    val commit: String?
)