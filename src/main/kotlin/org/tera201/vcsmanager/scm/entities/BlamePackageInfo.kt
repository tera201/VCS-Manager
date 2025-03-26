package org.tera201.vcsmanager.scm.entities

import org.eclipse.jgit.revwalk.RevCommit

data class BlamePackageInfo(
    val packageName: String,
    val commits: MutableSet<RevCommit> = HashSet(),
    var lineCount: Long = 0,
    var lineSize: Long = 0,
    val filesInfo: MutableMap<String, BlameFileInfo> = HashMap(),
    val authorInfo: MutableMap<String, BlameAuthorInfo> = HashMap()
) {

    fun add(fileInfo: BlameFileInfo, filePath: String) {
        filesInfo[filePath] = fileInfo
        lineCount += fileInfo.lineCount
        lineSize += fileInfo.lineSize
        commits.addAll(fileInfo.commits)

        fileInfo.authorInfos.values.forEach{ author ->
            authorInfo.computeIfAbsent(author.author) { BlameAuthorInfo(it) }.add(author)
        }
    }

    fun findLatestCommit(): RevCommit? = commits.maxByOrNull { it.commitTime }
}
