package org.tera201.vcsmanager.scm.services

import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tera201.vcsmanager.db.VCSDataBase
import org.tera201.vcsmanager.db.entities.*
import org.tera201.vcsmanager.scm.RepositoryFile
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.*

class GitRepositoryUtil(val gitOps: GitOperations, val vcsDataBase: VCSDataBase, val projectId: Int) {
    private val log: Logger = LoggerFactory.getLogger(GitRepositoryUtil::class.java)
    private val fileSizeCache = mutableMapOf<String, Long?>()

    suspend fun dbPrepared(filePathMap: ConcurrentHashMap<String, Long>) {
        coroutineScope {
            gitOps.git.use { git ->
                val commits = git.log().call().toList()
                log.info("Commits in total: ${commits.size}")
                val filtered =
                    commits.filter { commit -> !vcsDataBase.isCommitExist(commit.name) && commit.parentCount == 1 }
                log.info("Commit after filter: ${filtered.size}")
                val authorIdCache = ConcurrentHashMap<String, Long>()
                val filePathMapCache = ConcurrentHashMap<String, Long>()

                filtered.map { commit ->
                    launch(Dispatchers.Default) {
                        val dbAsync = VCSDataBase(vcsDataBase.url)
                        val paths = getCommitsFiles(commit, git)
                        withContext(Dispatchers.IO) {
                            authorIdCache.computeIfAbsent(commit.authorIdent.emailAddress) { email ->
                                dbAsync.getAuthorId(projectId, email)
                                    ?: dbAsync.insertAuthor(projectId, commit.authorIdent.name, email)!!
                            }
                            paths.keys.map { filePath ->
                                filePathMapCache.computeIfAbsent(filePath) { path ->
                                    dbAsync.getFilePathId(projectId, path)
                                        ?: dbAsync.insertFilePath(projectId, path)
                                }
                            }
                        }
                        val stability =
                            CommitStabilityAnalyzer.analyzeCommit(git, commits, commit, commits.indexOf(commit))
                        val commitSize = processCommitSize(commit, git)
                        if (paths.isEmpty()) return@launch
                        val fileEntity = paths.values.reduce { acc, fileEntity -> acc.add(fileEntity); acc }
                        val authorId = authorIdCache[commit.authorIdent.emailAddress]!!
                        withContext(Dispatchers.IO) {
                            dbAsync.insertCommit(projectId, authorId, commit, commitSize, stability, fileEntity)
                            val fileList: MutableList<FileEntity> = mutableListOf()
                            paths.keys.forEach { filePath ->
                                fileList.add(
                                    FileEntity(
                                        projectId,
                                        filePath,
                                        filePathMapCache[filePath]!!,
                                        commit.name,
                                        commit.commitTime
                                    )
                                )
                            }
                            dbAsync.insertFile(fileList)
                        }
                        dbAsync.closeConnection()
                    }
                }.joinAll()
                filePathMap.putAll(vcsDataBase.getAllFilePaths(projectId))
            }
        }
    }

    suspend fun getDeveloperInfo(
        filePathMap: ConcurrentHashMap<String, Long>,
        developersMap: ConcurrentHashMap<String, DeveloperInfo>,
        path: String,
        nodePath: String?,
        repositoryFiles: List<RepositoryFile>
    ): Map<String, DeveloperInfo> {
        return coroutineScope {
            gitOps.git.use { git ->
                val localPath = nodePath?.takeIf { it != path && it.startsWith(path) }
                    ?.substring(path.length + 1)?.replace("\\", "/")
                val commits = if (localPath != null) git.log().addPath(localPath).call() else git.log().call()
                commits.map { commit ->
                    launch {
                        val dbAsync = VCSDataBase(vcsDataBase.url)
                        dbAsync.getCommit(projectId, commit.name)?.let { commitEntity ->
                            developersMap.computeIfAbsent(commitEntity.authorEmail) {
                                DeveloperInfo(commitEntity, commit)
                            }.updateByCommit(commitEntity, commit)
                        }
                        dbAsync.closeConnection()

                    }
                }.joinAll()


                val head = git.repository.resolve(Constants.HEAD)
                val fileStream = repositoryFiles
                    .filter { (it.file.path.startsWith(localPath ?: "") && !it.file.path.endsWith(".DS_Store")) }
                    .map { it.file.path.substring(path.length + 1).replace("\\", "/") }
                    .filter { filePathMap.keys.contains(it) }
                fileStream.filter {
                    vcsDataBase.getBlameFileId(projectId, filePathMap[it]!!, head.name) == null
                }.forEach { vcsDataBase.insertBlameFile(projectId, filePathMap[it]!!, head.name) }
                val fileAndBlameId = fileStream.map {
                    it to vcsDataBase.getBlameFileId(projectId, filePathMap[it]!!, head.name)!!
                }

                val devs = vcsDataBase.getDevelopersByProjectId(projectId)
                fileAndBlameId.map { (filePath, blameId) ->
                    launch(Dispatchers.Default) {
                        val dbAsync = VCSDataBase(vcsDataBase.url)
                        runCatching {
                            git.blame().setFilePath(filePath).setStartCommit(head).call()?.let {
                                updateFileOwner(it, devs, dbAsync, projectId, blameId, head.name)
                            }
                            dbAsync.updateBlameLineSize(blameId)
                        }.onFailure { it.printStackTrace() }
                        dbAsync.closeConnection()
                    }
                }.joinAll()

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

    fun updateFileOwner(
        blameResult: BlameResult,
        devs: Map<String, Long>,
        dataBase: VCSDataBase,
        projectId: Int,
        blameFileId: Int,
        headHash: String
    ) {
        val blameEntities = mutableMapOf<String, BlameEntity>()
        for (i in 0..<blameResult.resultContents.size()) {
            val author = blameResult.getSourceAuthor(i)
            val lineSize = blameResult.resultContents.getString(i).toByteArray().size.toLong()
            blameEntities.computeIfAbsent(author.emailAddress) {
                BlameEntity(projectId, devs[it] ?: -1, blameFileId, headHash, mutableListOf(), 0)
            }.apply {
                lineIds.add(i)
                this.lineSize += lineSize
            }
        }

        dataBase.insertBlame(blameEntities.values.toList())
    }

    fun repositorySize(repoPath: String, filePath: String?): Map<String, CommitSize> {
        val localPath = filePath?.takeIf { it != repoPath && it.startsWith(repoPath) }
            ?.substring(repoPath.length + 1)?.replace("\\", "/") ?: ""
        return vcsDataBase.getCommitSizeMap(projectId, localPath)
    }
}
