package org.tera201.vcsmanager.domain

import java.util.*

data class Commit(
    val hash: String,
    val author: Developer,
    val committer: Developer?,
    val authorDate: Calendar?,
    val authorTimeZone: TimeZone = TimeZone.getDefault(),
    val committerDate: Calendar?,
    val committerTimeZone: TimeZone = TimeZone.getDefault(),
    val msg: String,
    val parents: List<String?> = emptyList(),
    val isMerge: Boolean = false,
    val branches: Set<String?> = emptySet(),
    val isInMainBranch: Boolean = false
) {
    val modifications = mutableListOf<Modification>()

    @get:Deprecated("")
    val parent: String
        get() = parents.firstOrNull() ?: ""

    override fun toString(): String {
        return ("Commit [hash=" + hash + ", parents=" + parents + ", author=" + author + ", msg=" + msg + ", modifications="
                + modifications + "]")
    }
}
