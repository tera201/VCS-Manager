package org.tera201.vcsmanager.scm.services

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GitRepositoryUtil(
    val gitOps: GitOperations,
    val vcsDataBase: VCSDataBase,
    val projectId: Int,
    val filePathMap: ConcurrentHashMap<String, Long>,
    val developersMap: ConcurrentHashMap<String, DeveloperInfo>
) {
    private val log: Logger = LoggerFactory.getLogger(GitRepositoryUtil::class.java)
    private val fileSizeCache = mutableMapOf<String, Long?>()


    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun dbPrepared(project: Project):Unit =
        suspendCancellableCoroutine {  continuation ->
            val task = object : Task.Backgroundable(project, "Preparing Repository Data", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.text = "Reading commits..."

                    try {
                        val git = gitOps.git
                        val allRefs = git.repository.refDatabase.getRefsByPrefix("refs/")
                        val logCommand = git.log()
                        for (ref in allRefs) {
                            logCommand.add(ref.objectId)
                        }
                        val commits = logCommand.call().toList()
                        val filtered =
                            commits.filter { commit -> !vcsDataBase.isCommitExist(commit.name) && commit.parentCount <= 1 }
                        val authorIdCache = ConcurrentHashMap<String, Long>()
                        val filePathMapCache = ConcurrentHashMap<String, Long>()
                        val dbAsync = VCSDataBase(vcsDataBase.url)

                            runBlocking {
                                filtered.mapIndexed { index, commit ->
                                    launch(Dispatchers.Default.limitedParallelism(10)) {
                                        if (indicator.isCanceled) throw kotlinx.coroutines.CancellationException()
                                        indicator.fraction = index.toDouble() / (filtered.size + 1)
                                        indicator.text2 =
                                            "Processing commit ${commit.name.take(8)} (${index + 1}/${filtered.size})"

                                        val paths = getCommitsFiles(commit, git)

                                        withContext(Dispatchers.IO) {
                                            if (indicator.isCanceled) throw kotlinx.coroutines.CancellationException()
                                            authorIdCache.computeIfAbsent(commit.authorIdent.emailAddress) {
                                                dbAsync.getAuthorId(projectId, commit.authorIdent.emailAddress)
                                                    ?: dbAsync.insertAuthor(projectId, commit.authorIdent.name, commit.authorIdent.emailAddress)
                                            }
                                            paths.keys.map { filePath ->
                                                    val cachePath = filePathMapCache[filePath]
                                                    if (cachePath != null) return@map cachePath
                                                    val compute = dbAsync.getFilePathId(projectId, filePath)
                                                        ?: dbAsync.insertFilePath(projectId, filePath)
                                                    filePathMapCache.computeIfAbsent(filePath) { compute }
                                                }
                                        }

                                        if (indicator.isCanceled) throw kotlinx.coroutines.CancellationException()
                                        val stability = CommitStabilityAnalyzer.analyzeCommit(git, commits, commit, commits.indexOf(commit))
                                        val commitSize = processCommitSize(commit, git)
                                        if (paths.isEmpty()) return@launch
                                        val fileEntity = paths.values.reduce { acc, fileEntity -> acc.add(fileEntity); acc }
                                        val authorId = authorIdCache[commit.authorIdent.emailAddress]!!

                                        withContext(Dispatchers.IO) {
                                            if (indicator.isCanceled) throw kotlinx.coroutines.CancellationException()
                                            dbAsync.insertCommit(projectId, authorId, commit, commitSize, stability, fileEntity)
                                            dbAsync.insertCommitMessage(projectId, commit)
                                            val fileList: MutableList<FileEntity> = mutableListOf()
                                            paths.keys.filter { filePathMapCache.containsKey(it) }.forEach { filePath ->
                                                val fileId = filePathMapCache[filePath]!!
                                                fileList.add(
                                                    FileEntity(projectId, filePath, fileId, commit.name, commit.commitTime)
                                                )
                                            }
                                            dbAsync.insertFile(fileList)
                                        }
                                    }
                                }
                                indicator.fraction = 1.0
                            }
                        filePathMap.putAll(vcsDataBase.getAllFilePaths(projectId))
                        prepareBranchInfo()

                        continuation.resume(Unit)
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            continuation.cancel()
                        } else {
                            continuation.resumeWithException(e)
                        }
                    }
                }

                override fun onCancel() {
                    if (continuation.isActive) continuation.cancel()
                }

                override fun onThrowable(error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            ProgressManager.getInstance().run(task)
        }


    @Deprecated("Use dbPrepared instead with project parameter", ReplaceWith("dbPrepared(project)"))
    suspend fun dbPrepared() {
        coroutineScope {
            val git = gitOps.git
            val allRefs = git.repository.refDatabase.getRefsByPrefix("refs/")
            val logCommand = git.log()
            for (ref in allRefs) {
                logCommand.add(ref.objectId)
            }
            val commits = logCommand.call().toList()
            log.info("Commits in total: ${commits.size}")
            val filtered =
                commits.filter { commit -> !vcsDataBase.isCommitExist(commit.name) && commit.parentCount <= 1 }
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
                                ?: dbAsync.insertAuthor(projectId, commit.authorIdent.name, email)
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
                        dbAsync.insertCommitMessage(projectId, commit)
                        val fileList: MutableList<FileEntity> = mutableListOf()
                        paths.keys.filter { filePathMapCache.containsKey(it) }.forEach { filePath ->
                            val fileId = filePathMapCache[filePath]!!
                            fileList.add(FileEntity(projectId, filePath, fileId, commit.name, commit.commitTime))
                        }
                        dbAsync.insertFile(fileList)
                    }
                }
            }.joinAll()
            filePathMap.putAll(vcsDataBase.getAllFilePaths(projectId))

            prepareBranchInfo()
        }
    }

    fun prepareBranchInfo() {
        val git = gitOps.git
        val db = VCSDataBase(vcsDataBase.url)
        val revWalk = RevWalk(git.repository)
        for (ref in gitOps.getAllBranches()) {
            val branchId = db.getBranchId(projectId, ref.name) ?: db.insertBranch(projectId, ref.name)
            if (branchId == -1) {
                log.error("Failed to insert branch: ${ref.name}")
                continue
            }
            val headCommit = revWalk.parseCommit(ref.objectId)
            val commits = git.log().add(headCommit).call().filter { commit -> vcsDataBase.isCommitExist(commit.name) }

            for (commit in commits) {
                db.insertBranchCommit(projectId, branchId, commit.name)
            }
        }
    }

    suspend fun getDeveloperInfo(
        path: String,
        nodePath: String?,
        repositoryFiles: List<RepositoryFile>
    ): Map<String, DeveloperInfo> {
        developersMap.clear() // Clear cache for the next usage
        return coroutineScope {
            val git = gitOps.git
            val localPath = nodePath?.takeIf { it != path && it.startsWith(path) }
                ?.extractLocalPath(path)
            val commits = if (localPath != null) git.log().addPath(localPath).call() else git.log().call()
            commits.map { commit ->
                launch {
                    val dbAsync = VCSDataBase(vcsDataBase.url)
                    dbAsync.getCommit(projectId, commit.name)?.let { commitEntity ->
                        developersMap.computeIfAbsent(commitEntity.authorEmail) {
                            DeveloperInfo(commitEntity, commit)
                        }.updateByCommit(commitEntity, commit)
                    }
                }
            }.joinAll()

            val head = git.repository.resolve(Constants.HEAD)
            val fileStream = repositoryFiles
                .filter { (it.file.path.startsWith(localPath ?: "") && !it.file.path.endsWith(".DS_Store")) }
                .map { it.file.path.extractLocalPath(path) }
                .filter { filePathMap.containsKey(it) }
                .filter { !vcsDataBase.isBlameExist(projectId, filePathMap[it]!!, head.name) }
            fileStream.forEach { vcsDataBase.insertBlameFile(projectId, filePathMap[it]!!, head.name) }
            val fileAndBlameId = fileStream.map {
                it to vcsDataBase.getBlameFileId(projectId, filePathMap[it]!!, head.name)!!
            }

            val devs = vcsDataBase.getDevelopersByProjectId(projectId)
            fileAndBlameId.filter { !vcsDataBase.isBlameExist(projectId, it.second) }.map { (filePath, blameId) ->
                launch(Dispatchers.Default) {
                    val dbAsync = VCSDataBase(vcsDataBase.url)
                    runCatching {
                        git.blame().setFilePath(filePath).setStartCommit(head).call()?.let {
                            updateFileOwner(it, devs, dbAsync, projectId, blameId)
                        }
                        dbAsync.updateBlameLineSize(blameId)
                    }.onFailure { it.printStackTrace() }
                }
            }.joinAll()

            vcsDataBase.developerUpdateByBlameInfo(projectId, developersMap)
            developersMap
        }
    }

    // TODO make more trivial message about large file
    fun getCommitChanges(hash: String): Map<String, Pair<String, String>> {
        val git = gitOps.git
        val revWalk = RevWalk(git.repository)
        val currentCommit = revWalk.parseCommit(git.repository.resolve(hash))
        val parent = currentCommit.parents.firstOrNull()?.let { revWalk.parseCommit(it) }
        val oldTreeIter = CanonicalTreeParser()
        val newTreeIter = CanonicalTreeParser()
        val reader = git.repository.newObjectReader()
        parent?.tree?.let { oldTreeIter.reset(reader, it) }
        newTreeIter.reset(reader, currentCommit.tree)

        val diffFormatter = DiffFormatter(DisabledOutputStream.INSTANCE).apply {
            setRepository(git.repository)
            isDetectRenames = true
        }

        val diffs = diffFormatter.scan(oldTreeIter, newTreeIter)

        if (diffs.size > 100) {
            return mapOf("Too big: Changes exceed limit" to Pair("Too big: Changes exceed limit", "Too big: Changes exceed limit"))
        }

        return diffs.associate { diff ->
                val path = when (diff.changeType) {
                    DiffEntry.ChangeType.ADD -> diff.newPath
                    DiffEntry.ChangeType.DELETE -> diff.oldPath
                    else -> diff.newPath
                }
                "${diff.changeType}: $path" to Pair(diffFormatter.getOldText(diff), diffFormatter.getNewText(diff))
        }
    }

    fun getCommitsFiles(commit: RevCommit, git: Git): Map<String, FileChangeEntity> {
        val out = ByteArrayOutputStream()
        val paths: MutableMap<String, FileChangeEntity> = HashMap()
        DiffFormatter(out).use { diffFormatter ->
            diffFormatter.setRepository(git.repository)
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT)
            diffFormatter.isDetectRenames = true
            val parent = commit.parents.firstOrNull()
            diffFormatter.scan(parent, commit).forEach { diff ->
                val fileChangeEntity = paths.getOrPut(diff.newPath) { FileChangeEntity() }
                fileChangeEntity.applyChanges(diff, diffFormatter, out.size())
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
                        runCatching {
                            reader.open(objectId).takeIf { it.type == Constants.OBJ_BLOB }?.size
                        }.getOrNull()
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
    ) {
        val blameEntities = mutableMapOf<String, BlameEntity>()
        for (i in 0..<blameResult.resultContents.size()) {
            val author = blameResult.getSourceAuthor(i)
            val lineSize = blameResult.resultContents.getString(i).toByteArray().size.toLong()
            blameEntities.computeIfAbsent(author.emailAddress) {
                BlameEntity(projectId, devs[it] ?: -1, blameFileId, mutableListOf(), 0)
            }.apply {
                lineIds.add(i)
                this.lineSize += lineSize
            }
        }

        dataBase.insertBlame(blameEntities.values.toList())
    }

    fun repositorySize(repoPath: String, filePath: String?): Map<String, CommitSize> {
        val localPath = filePath?.takeIf { it != repoPath && it.startsWith(repoPath) }?.extractLocalPath(repoPath) ?: ""
        return vcsDataBase.getCommitSizeMap(projectId, localPath)
    }

    private fun String.extractLocalPath(path: String): String {
        return this.substring(path.length + 1).replace("\\", "/")
    }

    fun DiffFormatter.getOldText(diff: DiffEntry): String {
        val maxChangeSize = 500000
        val oldId = diff.oldId.toObjectId()
        if (oldId == null || oldId.name == "0000000000000000000000000000000000000000") return ""

        if (diff.oldId.toObjectId()?.let { gitOps.git.repository.open(it).size } ?: 0 > maxChangeSize) return "Too big"
        val loader: ObjectLoader = gitOps.git.repository.open(oldId)
        return loader.bytes.toString(Charsets.UTF_8)
    }

    fun DiffFormatter.getNewText(diff: DiffEntry): String {
        val maxChangeSize = 500000
        val newId = diff.newId.toObjectId()
        if (newId == null || newId.name == "0000000000000000000000000000000000000000") return ""

        if (diff.newId.toObjectId()?.let { gitOps.git.repository.open(it).size } ?: 0 > maxChangeSize) return "Too big"
        val loader: ObjectLoader = gitOps.git.repository.open(newId)
        return loader.bytes.toString(Charsets.UTF_8)
    }

    private val dbDispatcher = newSingleThreadContext("DB-Dispatcher")
}
