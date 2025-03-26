package org.tera201.vcsmanager.scm.entities

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.tera201.vcsmanager.util.CommitEntity
import org.tera201.vcsmanager.util.FileEntity
import java.io.ByteArrayOutputStream
import java.io.IOException


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
        commitEntity.fileEntity.apply {
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
            commitEntity.fileEntity.apply {
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

    fun processFileEntity(fileEntity: FileEntity) {
        this.fileAdded += fileEntity.linesAdded.toLong()
        this.fileDeleted += fileEntity.linesDeleted.toLong()
        this.fileModified += fileEntity.linesModified.toLong()
        this.linesAdded += fileEntity.linesAdded.toLong()
        this.linesDeleted += fileEntity.linesDeleted.toLong()
        this.linesModified += fileEntity.linesModified.toLong()
        this.changes += fileEntity.changes.toLong()
        this.changesSize += fileEntity.changesSize.toLong()
    }

    @Throws(IOException::class)
    fun processDiff(diff: DiffEntry, repository: Repository?) {
        val out = ByteArrayOutputStream()


        when (diff.changeType) {
            DiffEntry.ChangeType.ADD -> {this.authorForFiles.add(diff.newPath); fileAdded++}
            DiffEntry.ChangeType.DELETE -> fileDeleted++
            DiffEntry.ChangeType.MODIFY -> fileModified++
            DiffEntry.ChangeType.RENAME -> TODO()
            DiffEntry.ChangeType.COPY -> TODO()
        }
        DiffFormatter(out).use { diffFormatter ->
            diffFormatter.setRepository(repository)
            diffFormatter.isDetectRenames = true
            diffFormatter.format(diff)

            val editList = diffFormatter.toFileHeader(diff).toEditList()
            for (edit in editList) {
                when (edit.type) {
                    Edit.Type.INSERT -> {
                        linesAdded += edit.lengthB.toLong()
                        changes += edit.lengthB.toLong()
                    }

                    Edit.Type.DELETE -> {
                        linesDeleted += edit.lengthA.toLong()
                        changes += edit.lengthA.toLong()
                    }

                    Edit.Type.REPLACE -> {
                        //TODO getLengthA (removed)  getLengthB (added) - maybe max(A,B) or just B
                        linesModified += (edit.lengthA + edit.lengthB).toLong()
                        changes += (edit.lengthA + edit.lengthB).toLong()
                    }

                    Edit.Type.EMPTY -> TODO()
                }
            }
        }
        changesSize += out.size().toLong()
    }
}
