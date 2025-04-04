package org.tera201.vcsmanager.scm.services

import kotlinx.coroutines.*
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
import org.tera201.vcsmanager.db.VCSDataBase
import org.tera201.vcsmanager.db.entities.*
import org.tera201.vcsmanager.scm.RepositoryFile
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.*

object GitRepositoryUtil {
    private val fileSizeCache = mutableMapOf<String, Long?>()

    suspend fun dbPrepared(
        git: Git,
        vcsDataBase: VCSDataBase,
        projectId: Int,
        filePathMap: ConcurrentHashMap<String, Long>,
        developersMap: ConcurrentHashMap<String, DeveloperInfo>
    ) {
        developersMap.clear()

        coroutineScope {
            git.use { git ->
                val commits = git.log().call().toList()
                println("Commits in total: ${ commits.size}")
                val fileter = commits.filter { commit ->!vcsDataBase.isCommitExist(commit.name) && commit.parentCount == 1 }
                println("Commit after filter: ${fileter.size}")
                val authorIdCache = ConcurrentHashMap<String, Long>()

                fileter.map { bunch ->
                    launch(Dispatchers.Default) {
                        val dbAsync = VCSDataBase(vcsDataBase.url)
                        val paths = getCommitsFiles(bunch, git)
                        withContext(Dispatchers.IO) {
                            authorIdCache.computeIfAbsent(bunch.authorIdent.emailAddress) { email ->
                                dbAsync.getAuthorId(projectId, email)
                                    ?: dbAsync.insertAuthor(projectId, bunch.authorIdent.name, email)!!
                            }
                            paths.keys.map { filePath ->
                                filePathMap.computeIfAbsent(filePath) { path ->
                                    dbAsync.getFilePathId(projectId, path)
                                        ?: dbAsync.insertFilePath(projectId, path)
                                }
                            }
                        }
                        val stability =
                            CommitStabilityAnalyzer.analyzeCommit(git, commits, bunch, commits.indexOf(bunch))
                        val commitSize = processCommitSize(bunch, git)
                        if (paths.isEmpty()) return@launch
                        val fileEntity = paths.values.reduce { acc, fileEntity -> acc.add(fileEntity); acc }
                        val authorId = authorIdCache[bunch.authorIdent.emailAddress]!!
                        withContext(Dispatchers.IO) {
                            dbAsync.insertCommit(projectId, authorId, bunch, commitSize, stability, fileEntity)
                            val fileList: MutableList<FileEntity> = mutableListOf()
                            paths.keys.forEach { filePath ->
                                fileList.add(
                                    FileEntity(
                                        projectId, filePath, filePathMap[filePath]!!, bunch.name, bunch.commitTime
                                    )
                                )
                            }
                            dbAsync.insertFile(fileList)
                        }
                        dbAsync.closeConnection()
                    }
                }.joinAll()
            }
        }
    }

    fun getDeveloperInfo(
        git: Git,
        vcsDataBase: VCSDataBase,
        projectId: Int,
        filePathMap: ConcurrentHashMap<String, Long>,
        developersMap: ConcurrentHashMap<String, DeveloperInfo>,
        path: String,
        nodePath: String?,
        repositoryFiles: List<RepositoryFile>
    ): Map<String, DeveloperInfo> {
        return runBlocking {
            git.use { git ->
                val localPath = nodePath?.takeIf { it != path && it.startsWith(path) }
                    ?.substring(path.length + 1)?.replace("\\", "/")
                val commits = if (localPath != null) git.log().addPath(localPath).call() else git.log().call()

                val commitJobs = commits.map { commit ->
                    async {
                        val dbAsync = VCSDataBase(vcsDataBase.url)
                        dbAsync.getCommit(projectId, commit.name)?.let { commitEntity ->
                            developersMap.computeIfAbsent(commitEntity.authorEmail) {
                                DeveloperInfo(commitEntity, commit)
                            }
                                .updateByCommit(commitEntity, commit)
                        }
                        dbAsync.closeConnection()

                    }
                }
                commitJobs.awaitAll()

                val head = git.repository.resolve(Constants.HEAD)
                val fileStream = repositoryFiles
                    .filter { (it.file.path.startsWith(localPath ?: "") && !it.file.path.endsWith(".DS_Store")) }
                    .map { it.file.path.substring(path.length + 1).replace("\\", "/") }
                    .filter { filePathMap.keys.contains(it) }
                val fileHashes =
                    fileStream.map { it to head.name }
                        .filter {
                            vcsDataBase
                                .getBlameFileId(projectId, filePathMap[it.first]!!, it.second!!) == null
                        }
                val fileAndBlameHashes = fileHashes.map {
                    it.first to vcsDataBase.insertBlameFile(
                        projectId,
                        filePathMap[it.first]!!,
                        it.second!!
                    )
                }

                val devs = vcsDataBase.getDevelopersByProjectId(projectId)

                val fileProcessingJobs = fileAndBlameHashes.toSet().map { (filePath, blameId) ->
                    async(Dispatchers.IO) {
                        val dbAsync = VCSDataBase(vcsDataBase.url)
                        runCatching {
                            git.blame().setFilePath(filePath).setStartCommit(head).call()?.let {
                                updateFileOwner(it, devs, dbAsync, projectId, blameId, head.name)
                            }
                            dbAsync.updateBlameFileSize(blameId)
                        }.onFailure { it.printStackTrace() }
                        dbAsync.closeConnection()

                    }
                }
                fileProcessingJobs.awaitAll()

                vcsDataBase.developerUpdateByBlameInfo(projectId, developersMap)
            }
            developersMap
        }
    }

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

    fun getCommitsFiles(commit: RevCommit, git: Git): Map<String, FileChangeEntity> {
        val out = ByteArrayOutputStream()
        val paths: MutableMap<String, FileChangeEntity> = HashMap()
        DiffFormatter(out).use { diffFormatter ->
            diffFormatter.setRepository(git.repository)
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT)
            diffFormatter.isDetectRenames = true
            commit.parents.firstOrNull()?.let { parent ->
                diffFormatter.scan(parent, commit).forEach { diff ->
                    val fileChangeEntity = paths.getOrPut(diff.newPath) { FileChangeEntity() }
                    fileChangeEntity.applyChanges(diff, diffFormatter, out.size())
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

    fun updateFileOwner(blameResult: BlameResult, developers: Map<String?, DeveloperInfo>) {
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

    fun repositorySize(vcsDataBase: VCSDataBase, projectId: Int, repoPath: String, filePath: String?): Map<String, CommitSize> {
            val localPath = filePath?.takeIf { it != repoPath && it.startsWith(repoPath) }
                ?.substring(repoPath.length + 1)?.replace("\\", "/") ?: ""
        return vcsDataBase.getCommitSizeMap(projectId, localPath)
    }

    fun updateFileOwner(
        blameResult: BlameResult,
        devs: Map<String, String>,
        vcsDataBase: VCSDataBase,
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

        vcsDataBase.insertBlame(blameEntities.values.toList())
    }

    private fun FileChangeEntity.applyChanges(diff: DiffEntry, diffFormatter: DiffFormatter, diffSize: Int) {
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
