package org.tera201.vcsmanager.scm.entities

import org.eclipse.jgit.revwalk.RevCommit

data class BlameAuthorInfo(
    var author: String,
    val commits: MutableSet<RevCommit> = mutableSetOf(),
    var lineCount: Long = 0,
    var lineSize: Long = 0
) {
    fun add(other: BlameAuthorInfo) {
        commits.addAll(other.commits)
        lineCount += other.lineCount
        lineSize += other.lineSize
    }

    override fun toString(): String {
        return "Author: $author, LineCount: $lineCount, LineSize: $lineSize, Commits: $commits"
    }
}
