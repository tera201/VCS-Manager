package org.tera201.vcsmanager.domain

import org.eclipse.jgit.revwalk.RevCommit
import java.util.*

data class Commit(
    val hash: String,
    val author: Developer,
    val committer: Developer,
    val authorDate: Date,
    val authorTimeZone: TimeZone,
    val committerDate: Date,
    val committerTimeZone: TimeZone,
    val msg: String = "",
    val parents: List<String?> = emptyList(),
    val isMerge: Boolean = false,
    val branches: Set<String?> = emptySet(),
    val isInMainBranch: Boolean = false,
    val modifications: MutableList<Modification> = mutableListOf()
) {
    constructor(commit: RevCommit, msg: String, branches: Set<String?>, isInMainBranch: Boolean) :
            this(
                commit.name,
                Developer(commit.authorIdent.name, commit.authorIdent.emailAddress),
                Developer(commit.committerIdent.name, commit.committerIdent.emailAddress),
                Date.from(commit.authorIdent.whenAsInstant),
                TimeZone.getTimeZone(commit.authorIdent.zoneId),
                Date.from(commit.committerIdent.whenAsInstant),
                TimeZone.getTimeZone(commit.committerIdent.zoneId),
                msg,
                commit.parents.map { it.name },
                commit.parentCount > 1,
                branches,
                isInMainBranch
            )

    @get:Deprecated("")
    val parent: String
        get() = parents.firstOrNull() ?: ""

    override fun toString(): String {
        return ("Commit [hash=" + hash + ", parents=" + parents + ", author=" + author + ", msg=" + msg + ", modifications="
                + modifications + "]")
    }
}
