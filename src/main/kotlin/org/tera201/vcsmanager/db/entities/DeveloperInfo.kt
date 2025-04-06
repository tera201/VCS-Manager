package org.tera201.vcsmanager.db.entities

import org.eclipse.jgit.revwalk.RevCommit


data class DeveloperInfo(
    var id: Long = -1,
    val name: String,
    val emailAddress: String,
    val commits: MutableSet<RevCommit> = HashSet(),
    var changes: Long = 0,
    var changesSize: Long = 0,
    var actualLinesOwner: Long = 0,
    var actualLinesSize: Long = 0,
    var linesAdded: Long = 0,
    var linesDeleted: Long = 0,
    var linesModified: Long = 0,
    var fileAdded: Long = 0,
    var fileDeleted: Long = 0,
    var fileModified: Long = 0,
    val authorForFiles: MutableList<String> = ArrayList(),
    var ownerForFiles: MutableList<String> = ArrayList()
) {

    constructor(commitEntity: CommitEntity, commit: RevCommit) : this(
        commitEntity.authorId,
        commitEntity.authorName,
        commitEntity.authorEmail
    ) {
        commits.add(commit)
        commitEntity.fileChangeEntity.apply {
            this@DeveloperInfo.changes = changes.toLong()
            this@DeveloperInfo.changesSize = changesSize.toLong()
            this@DeveloperInfo.linesAdded = linesAdded.toLong()
            this@DeveloperInfo.linesDeleted = linesDeleted.toLong()
            this@DeveloperInfo.linesModified = linesModified.toLong()
            this@DeveloperInfo.fileAdded = fileAdded.toLong()
            this@DeveloperInfo.fileDeleted = fileDeleted.toLong()
            this@DeveloperInfo.fileModified = fileModified.toLong()
        }
    }

    fun updateByCommit(commitEntity: CommitEntity, commit: RevCommit) {
        if (!commits.contains(commit)) {
            commits.add(commit)
            commitEntity.fileChangeEntity.apply {
                this@DeveloperInfo.changes += changes.toLong()
                this@DeveloperInfo.changesSize += changesSize.toLong()
                this@DeveloperInfo.linesAdded += linesAdded.toLong()
                this@DeveloperInfo.linesDeleted += linesDeleted.toLong()
                this@DeveloperInfo.linesModified += linesModified.toLong()
                this@DeveloperInfo.fileAdded += fileAdded.toLong()
                this@DeveloperInfo.fileDeleted += fileDeleted.toLong()
                this@DeveloperInfo.fileModified += fileModified.toLong()
            }
        }
    }
}
