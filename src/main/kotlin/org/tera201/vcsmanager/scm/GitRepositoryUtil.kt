package org.tera201.vcsmanager.scm

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.tera201.vcsmanager.scm.entities.DeveloperInfo
import org.tera201.vcsmanager.util.BlameEntity
import org.tera201.vcsmanager.util.DataBaseUtil
import org.tera201.vcsmanager.util.FileEntity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*

object GitRepositoryUtil {
    private val fileSizeCache = mutableMapOf<String, Long?>()

    @Throws(IOException::class)
    fun analyzeCommit(commit: RevCommit, git: Git, dev: DeveloperInfo) {
        DiffFormatter(DisabledOutputStream.INSTANCE).use { diffFormatter ->
            commit.parents[0]?.let { parent ->
                diffFormatter.apply {
                    setRepository(git.repository)
                    setDiffComparator(RawTextComparator.DEFAULT)
                    isDetectRenames = true
                }

                diffFormatter.scan(parent, commit).forEach { diff ->
                    dev.processDiff(diff, git.repository)
                }
            }
        }
    }

    @Throws(IOException::class)
    fun getCommitsFiles(commit: RevCommit, git: Git): Map<String, FileEntity> {
        val out = ByteArrayOutputStream()
        val paths: MutableMap<String, FileEntity> = HashMap()
        DiffFormatter(out).use { diffFormatter ->
            commit.parents[0]?.let { parent ->
                diffFormatter.apply {
                    setRepository(git.repository)
                    setDiffComparator(RawTextComparator.DEFAULT)
                    isDetectRenames = true
                }

                diffFormatter.scan(parent, commit).forEach { diff ->
                    val fileEntity = paths.getOrPut(diff.newPath) { FileEntity() }
                    fileEntity.applyChanges(diff, diffFormatter, out.size())
                }
            }
        }
        return paths
    }

    fun processCommitSize(commit: RevCommit, git: Git): Long {
        var projectSize = 0L
        git.repository.newObjectReader().use { reader ->
            TreeWalk(git.repository).use { treeWalk ->
                treeWalk.apply {
                    addTree(commit.tree)
                    isRecursive = true
                }

                while (treeWalk.next()) {
                    val objectId = treeWalk.getObjectId(0)
                    val objectHash = objectId.name
                    projectSize += fileSizeCache.getOrPut(objectHash) {
                        reader.open(objectId).takeIf { it.type == Constants.OBJ_BLOB }?.size
                    } ?: 0L
                }
            }
        }
        return projectSize
    }

    fun updateFileOwnerBasedOnBlame(blameResult: BlameResult, developers: Map<String?, DeveloperInfo>) {
        val linesOwners = mutableMapOf<String, Int>()
        val linesSizes = mutableMapOf<String, Long>()

        for (i in 0..<blameResult.resultContents.size()) {
            val authorEmail = blameResult.getSourceAuthor(i).emailAddress
            val lineSize = blameResult.resultContents.getString(i).toByteArray().size.toLong()
            linesSizes.merge(authorEmail, lineSize, Long::plus)
            linesOwners.merge(authorEmail, 1, Int::plus)
        }
        linesOwners.forEach { (key: String, value: Int) -> developers[key]!!.actualLinesOwner += value.toLong() }
        linesSizes.forEach { (key: String, value: Long) -> developers[key]!!.actualLinesSize += value }

        linesOwners.entries.maxByOrNull { it.value }?.also {
            developers[it.key]!!
                .ownerForFiles.add(blameResult.resultPath)
        }

    }

    fun updateFileOwnerBasedOnBlame(
        blameResult: BlameResult,
        devs: Map<String, String>,
        dataBaseUtil: DataBaseUtil,
        projectId: Int,
        blameFileId: Int,
        headHash: String
    ) {
        val blameEntities = mutableMapOf<String, BlameEntity>()
        for (i in 0..<blameResult.resultContents.size()) {
            val author = blameResult.getSourceAuthor(i)
            val commitHash = blameResult.getSourceCommit(i).name
            val lineSize = blameResult.resultContents.getString(i).toByteArray().size.toLong()
            blameEntities.computeIfAbsent(author.emailAddress) {
                BlameEntity(projectId, devs[it] ?: "", blameFileId, mutableListOf(), mutableListOf(), 0)
            }.apply {
                blameHashes.add(headHash)
                lineIds.add(i)
                this.lineSize += lineSize
            }
        }

        dataBaseUtil.insertBlame(blameEntities.values.toList())
    }

    private fun FileEntity.applyChanges(diff: DiffEntry, diffFormatter: DiffFormatter, diffSize: Int) {
        var fileAdded = 0
        var fileDeleted = 0
        var fileModified = 0
        var linesAdded = 0
        var linesDeleted = 0
        var linesModified = 0
        var changes = 0

        when (diff.changeType) {
            DiffEntry.ChangeType.ADD -> fileAdded++
            DiffEntry.ChangeType.DELETE -> fileDeleted++
            DiffEntry.ChangeType.MODIFY, DiffEntry.ChangeType.RENAME -> fileModified++
            DiffEntry.ChangeType.COPY -> fileAdded++
        }

        diffFormatter.toFileHeader(diff).toEditList().forEach { edit ->
            when (edit.type) {
                Edit.Type.INSERT -> {
                    linesAdded += edit.lengthB
                    changes += edit.lengthB
                }

                Edit.Type.DELETE -> {
                    linesDeleted += edit.lengthA
                    changes += edit.lengthA
                }

                Edit.Type.REPLACE -> {
                    linesModified += edit.lengthA + edit.lengthB
                    changes += edit.lengthA + edit.lengthB
                }

                Edit.Type.EMPTY -> return@forEach
            }
        }

        plus(fileAdded, fileDeleted, fileModified, linesAdded, linesDeleted, linesModified, changes, diffSize)
    }
}
