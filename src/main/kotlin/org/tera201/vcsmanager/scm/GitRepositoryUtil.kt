package org.tera201.vcsmanager.scm

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectReader
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
import java.util.concurrent.ConcurrentHashMap

object GitRepositoryUtil {
    private val fileSizeCache: MutableMap<String, Long?> = ConcurrentHashMap()

    @Throws(IOException::class)
    fun analyzeCommit(commit: RevCommit, git: Git, dev: DeveloperInfo) {
        DiffFormatter(DisabledOutputStream.INSTANCE).use { diffFormatter ->
            val parent = if (commit.parentCount > 0) commit.getParent(0) else null
            diffFormatter.setRepository(git.repository)
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT)
            diffFormatter.isDetectRenames = true
            val diffs = diffFormatter.scan(parent, commit)
            for (diff in diffs) {
                dev.processDiff(diff, git.repository)
            }
        }
    }

    @Throws(IOException::class)
    fun getCommitsFiles(commit: RevCommit, git: Git): Map<String, FileEntity> {
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { diffFormatter ->
            val parent = if (commit.parentCount > 0) commit.getParent(0) else null
            diffFormatter.setRepository(git.repository)
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT)
            diffFormatter.isDetectRenames = true
            val diffs = diffFormatter.scan(parent, commit)
            val paths: MutableMap<String, FileEntity> = HashMap()

            for (diff in diffs) {
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
                    DiffEntry.ChangeType.MODIFY -> fileModified++
                    DiffEntry.ChangeType.RENAME -> fileModified++
                    DiffEntry.ChangeType.COPY -> fileAdded++
                }

                val editList = diffFormatter.toFileHeader(diff).toEditList()
                for (edit in editList) {
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
                            //TODO getLengthA (removed)  getLengthB (added) - maybe max(A,B) or just B
                            linesModified += edit.lengthA + edit.lengthB
                            changes += edit.lengthA + edit.lengthB
                        }

                        Edit.Type.EMPTY -> TODO()
                    }
                }
                paths.putIfAbsent(diff.newPath, FileEntity())
                paths[diff.newPath]!!
                    .plus(
                        fileAdded,
                        fileDeleted,
                        fileModified,
                        linesAdded,
                        linesDeleted,
                        linesModified,
                        changes,
                        out.size()
                    )
            }
            return paths
        }
    }

    fun processCommitSize(commit: RevCommit, git: Git): Long {
        var projectSize: Long = 0
        var reader: ObjectReader? = null
        try {
            reader = git.repository.newObjectReader()
            TreeWalk(git.repository).use { treeWalk ->
                treeWalk.addTree(commit.tree)
                treeWalk.isRecursive = true
                while (treeWalk.next()) {
                    val objectId = treeWalk.getObjectId(0)
                    val objectHash = objectId.name
                    val size = fileSizeCache.getOrDefault(objectHash, null)
                    if (size == null) {
                        val loader = reader.open(objectId)
                        if (loader.type == Constants.OBJ_BLOB) {
                            projectSize += loader.size
                            fileSizeCache[objectHash] = loader.size
                        }
                    } else projectSize += size
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            reader?.close()
        }
        return projectSize
    }

    fun updateFileOwnerBasedOnBlame(blameResult: BlameResult, developers: Map<String?, DeveloperInfo>) {
        val linesOwners: MutableMap<String, Int> = HashMap()
        val linesSizes: MutableMap<String, Long> = HashMap()
        if (blameResult != null) {
            for (i in 0..<blameResult.resultContents.size()) {
                val authorEmail = blameResult.getSourceAuthor(i).emailAddress
                val lineSize = blameResult.resultContents.getString(i).toByteArray().size.toLong()
                linesSizes.merge(authorEmail, lineSize) { a: Long?, b: Long? ->
                    java.lang.Long.sum(
                        a!!,
                        b!!
                    )
                }
                linesOwners.merge(authorEmail, 1) { a: Int?, b: Int? -> Integer.sum(a!!, b!!) }
            }
            linesOwners.forEach { (key: String?, value: Int?) -> developers[key]!!.increaseActualLinesOwner(value.toLong()) }
            linesSizes.forEach { (key: String?, value: Long?) -> developers[key]!!.increaseActualLinesSize(value) }

            linesOwners.entries.maxByOrNull { it.value }?.also { developers[it.key]!!
                .addOwnedFile(blameResult.resultPath) }
        } else println("Blame for file " + blameResult.resultPath + " not found")
    }

    fun updateFileOwnerBasedOnBlame(
        blameResult: BlameResult,
        devs: Map<String?, String?>,
        dataBaseUtil: DataBaseUtil,
        projectId: Int,
        blameFileId: Int,
        headHash: String
    ) {
        val blameEntityMap: MutableMap<String, BlameEntity> = HashMap()
        if (blameResult != null) {
            for (i in 0..<blameResult.resultContents.size()) {
                val author = blameResult.getSourceAuthor(i)
                val commitHash = blameResult.getSourceCommit(i).name
                val lineSize = blameResult.resultContents.getString(i).toByteArray().size.toLong()
                val blameEntity = blameEntityMap.computeIfAbsent(author.emailAddress) { key: String? ->
                    BlameEntity(
                        projectId,
                        devs[key]!!, blameFileId, ArrayList(), ArrayList(), 0
                    )
                }
                blameEntity.blameHashes.add(headHash)
                blameEntity.lineIds.add(i)
                blameEntity.lineSize = blameEntity.lineSize + lineSize
            }
        } else println("Blame for file " + blameResult.resultPath + " not found")
        dataBaseUtil.insertBlame(blameEntityMap.values.stream().toList())
    }
}
