package org.tera201.vcsmanager.scm.entities

import org.eclipse.jgit.revwalk.RevCommit

data class BlameFileInfo(
    val fileName: String,
    var lineCount: Long = 0,
    var lineSize: Long = 0,
    val commits: MutableSet<RevCommit> = mutableSetOf(),
    val authorInfos: MutableMap<String, BlameAuthorInfo> = mutableMapOf()
) {

    fun add(authorInfo: BlameAuthorInfo) {
        authorInfos.computeIfAbsent(authorInfo.author) { BlameAuthorInfo(it) }
            .add(authorInfo)
        lineCount += authorInfo.lineCount
        lineSize += authorInfo.lineSize
        commits.addAll(authorInfo.commits)
    }

    fun findLatestCommit(): RevCommit? = commits.maxByOrNull { it.commitTime }
}
