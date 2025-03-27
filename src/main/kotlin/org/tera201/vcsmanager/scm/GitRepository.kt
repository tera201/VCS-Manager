package org.tera201.vcsmanager.scm

import kotlinx.coroutines.*
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tera201.vcsmanager.RepoDrillerException
import org.tera201.vcsmanager.domain.*
import org.tera201.vcsmanager.scm.entities.*
import org.tera201.vcsmanager.scm.exceptions.CheckoutException
import org.tera201.vcsmanager.util.DataBaseUtil
import org.tera201.vcsmanager.util.FileEntity
import org.tera201.vcsmanager.util.PathUtils
import org.tera201.vcsmanager.util.RDFileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.StreamSupport

/* TODO Name: Sounds like it inherits SCMRepository, but it actually implements SCM. */
open class GitRepository : SCM {
    private var mainBranchName: String? = null
    var maxNumberFilesInACommit: Int = -1 /* TODO Expose an API to control this value? Also in SubversionRepository. */
        private set
    private var maxSizeOfDiff = -1 /* TODO Expose an API to control this value? Also in SubversionRepository. */
    private var collectConfig: CollectConfiguration? = null
    private var repoName: String
    private var path: String
    protected var firstParentOnly = false
    var sizeCache: Map<ObjectId, Long> = ConcurrentHashMap()
    protected open var dataBaseUtil: DataBaseUtil? = null
    protected open var projectId: Int? = null
    override val changeSets: List<ChangeSet>
        get() = safeCall({
            git.use { git -> return if (!firstParentOnly) getAllCommits(git) else firstParentsOnly(git) }
        }, "error in getChangeSets for $path")

    /** Constructor, initializes the repository with given path and options */
    protected constructor() : this("")

    constructor(path: String, firstParentOnly: Boolean = false, dataBaseUtil: DataBaseUtil? = null) {
        log.debug("Creating a GitRepository from path $path")
        this.dataBaseUtil = dataBaseUtil
        this.path = path
        val splitPath = path.replace("\\", "/").split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        repoName = if (splitPath.isNotEmpty()) splitPath.last() else ""
        val projectId = dataBaseUtil?.getProjectId(repoName, path) ?: dataBaseUtil?.insertProject(repoName, path)
        this.firstParentOnly = firstParentOnly
        maxNumberFilesInACommit = checkMaxNumberOfFiles()
        maxSizeOfDiff = checkMaxSizeOfDiff()
        this.collectConfig = CollectConfiguration().everything()
    }

    override val info: SCMRepository
        get() = try {
            git.use { git ->
                val head = git.repository.resolve(Constants.HEAD)
                val rw = RevWalk(git.repository)
                val root = rw.parseCommit(head)
                rw.sort(RevSort.REVERSE)
                rw.markStart(root)
                val lastCommit = rw.next()
                val origin = git.repository.config.getString("remote", "origin", "url")
                repoName = if (origin != null) GitRemoteRepository.repoNameFromURI(origin) else repoName
                return SCMRepository(this, origin, repoName, path, head.name, lastCommit.name)
            }
        } catch (e: Exception) {
            throw RuntimeException("Couldn't create JGit instance with path $path")
        }

    @Deprecated("Use git")
    fun openRepository(): Git = Git.open(File(path)).also { git ->
        this.mainBranchName = this.mainBranchName ?: discoverMainBranchName(git)
    }

    val git: Git
        get() = Git.open(File(path)).also { git ->
            this.mainBranchName = this.mainBranchName ?: discoverMainBranchName(git)
        }

    private fun discoverMainBranchName(git: Git): String = git.repository.branch

    override val head: ChangeSet
        get() = safeCall({
            git.use { git ->
                val head = git.repository.resolve(Constants.HEAD)
                RevWalk(git.repository).use { revWalk ->
                    val r = revWalk.parseCommit(head)
                    ChangeSet(r.name, convertToDate(r))
                }
            }
        }, "Error in getHead() for $path")

    override fun createCommit(message: String) {
        safeCall({
            git.use { git ->
                val status = git.status().call()
                if (!status.hasUncommittedChanges()) return
                git.add().apply { status.modified.forEach { addFilepattern(it) }; call() }
                git.commit().setMessage(message).call()
            }
        }, "Error in create commit for $path")
    }

    override fun resetLastCommitsWithMessage(message: String) {
        safeCall({
            git.use { git ->
                val commit: RevCommit? = git.log().call().firstOrNull { it.fullMessage.contains(message) }
                if (commit != null) {
                    git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(extractChangeSet(commit).id).call()
                } else {
                    log.info("Reset doesn't required")
                }
            }
        }, "Reset failed ")
    }

    private fun firstParentsOnly(git: Git): List<ChangeSet> {
        val allCs: MutableList<ChangeSet> = mutableListOf()

        git.repository.findRef(Constants.HEAD)?.let { headRef ->
            RevWalk(git.repository).use { revWalk ->
                revWalk.revFilter = FirstParentFilter()
                revWalk.sort(RevSort.TOPO)

                val headCommit = revWalk.parseCommit(headRef.objectId)
                revWalk.markStart(headCommit)

                revWalk.forEach { allCs.add(extractChangeSet(it)) }
            }
        } ?: throw IllegalStateException("HEAD reference not found")

        return allCs
    }

    private fun getAllCommits(git: Git): List<ChangeSet> = git.log().call().map { extractChangeSet(it) }.toList()

    private fun extractChangeSet(r: RevCommit): ChangeSet = ChangeSet(r.name, convertToDate(r))

    private fun convertToDate(revCommit: RevCommit): GregorianCalendar = GregorianCalendar().apply {
        timeZone = TimeZone.getTimeZone(revCommit.authorIdent.zoneId)
        time = Date.from(revCommit.authorIdent.whenAsInstant)
    }

    /** Get the commit with this commit id. */
    override fun getCommit(id: String): Commit? {
        try {
            git.use { git ->
                val repo = git.repository
                val jgitCommit = git.log().add(repo.resolve(id)).call().firstOrNull() ?: return null

                /* Extract metadata. */
                val author = Developer(jgitCommit.authorIdent.name, jgitCommit.authorIdent.emailAddress)
                val committer = Developer(jgitCommit.committerIdent.name, jgitCommit.committerIdent.emailAddress)
                val msg = if (collectConfig?.isCollectingCommitMessages == true) {
                    jgitCommit.fullMessage.trim()
                } else {
                    ""
                }
                val branches = getBranches(git, jgitCommit.name)
                val isCommitInMainBranch = branches.contains(this.mainBranchName)

                /* Create one of our Commit's based on the jgitCommit metadata. */
                val commit = Commit(jgitCommit, author, committer, msg, branches, isCommitInMainBranch)

                /* Convert each of the associated DiffEntry's to a Modification. */
                val diffsForTheCommit = diffsForTheCommit(repo, jgitCommit) ?: return null
                if (diffsForTheCommit.size > maxNumberFilesInACommit) {
                    val errMsg = "Commit $id touches more than $maxNumberFilesInACommit files"
                    log.error(errMsg)
                    throw RepoDrillerException(errMsg)
                }

                diffsForTheCommit
                    .filter { diffFiltersAccept(it) }
                    .map { diffToModification(repo, it) }
                    .forEach { commit.modifications.add(it) }

                return commit
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("error detailing $id in $path", e)
        }
    }

    private fun getBranches(git: Git, hash: String): Set<String?> {
        if (!collectConfig!!.isCollectingBranches) return HashSet()

        val gitBranches = git.branchList().setContains(hash).call()
        return gitBranches.stream()
            .map { ref: Ref -> ref.name.substring(ref.name.lastIndexOf("/") + 1) }
            .collect(Collectors.toSet())
    }

    override val allBranches: List<Ref>
        get() = safeCall({
            git.use { git ->
                return git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()
            }
        }, "error getting branches in $path")

    override val allTags: List<Ref>
        get() = safeCall(
            { git.use { git -> return git.tagList().call() } },
            "Error getting tags in $path"
        )

    override fun checkoutTo(branch: String) {
        git.use { git ->
            if (git.repository.isBare) throw CheckoutException("Error repo is bare")
            if (git.repository.findRef(branch) == null) {
                throw CheckoutException("Branch does not exist: $branch")
            }

            if (git.status().call().hasUncommittedChanges()) {
                throw CheckoutException("There are uncommitted changes in the working directory")
            }
            safeCall({ git.checkout().setName(branch).call() }, "Error checking out to $branch")
        }
    }

    override val currentBranchOrTagName: String
        get() = safeCall({
            git.use { git ->
                val head = git.repository.resolve("HEAD")
                return git.repository.allRefsByPeeledObjectId[head]!!
                    .map { obj: Ref -> obj.name }
                    .distinct().first { it: String -> it.startsWith("refs/") }
            }
        }, "Error getting branch name")

    private fun diffToModification(repo: Repository, diff: DiffEntry): Modification {
        val change = enumValueOf<ModificationType>(diff.changeType.toString())

        val oldPath = diff.oldPath
        val newPath = diff.newPath

        var diffText = ""
        var sc = ""
        if (diff.changeType != DiffEntry.ChangeType.DELETE) {
            diffText = getDiffText(repo, diff)
            sc = getSourceCode(repo, diff)
        }

        if (diffText.length > maxSizeOfDiff) {
            log.error("diff for $newPath too big")
            diffText = "-- TOO BIG --"
        }

        return Modification(oldPath, newPath, change, diffText, sc)
    }

    private fun diffsForTheCommit(repo: Repository, commit: RevCommit): List<DiffEntry>? {
        val currentCommit: AnyObjectId = repo.resolve(commit.name)
        val parentCommit: AnyObjectId? = if (commit.parentCount > 0) repo.resolve(commit.getParent(0).name) else null

        return this.getDiffBetweenCommits(repo, parentCommit, currentCommit)
    }

    override fun getDiffBetweenCommits(priorCommitHash: String, laterCommitHash: String): List<Modification> {
        try {
            git.use { git ->
                val repo = git.repository
                val priorCommit: AnyObjectId = repo.resolve(priorCommitHash)
                val laterCommit: AnyObjectId = repo.resolve(laterCommitHash)

                val diffs = this.getDiffBetweenCommits(repo, priorCommit, laterCommit)
                val modifications = diffs!!
                    .map { diff: DiffEntry ->
                        try {
                            return@map this.diffToModification(repo, diff)
                        } catch (e: IOException) {
                            throw RuntimeException("error diffing $priorCommitHash and $laterCommitHash in $path", e)
                        }
                    }
                git.close()
                return modifications
            }
        } catch (e: Exception) {
            throw RuntimeException(
                "error diffing $priorCommitHash and $laterCommitHash in $path",
                e
            )
        }
    }

    private fun getDiffBetweenCommits(
        repo: Repository, parentCommit: AnyObjectId?,
        currentCommit: AnyObjectId
    ): List<DiffEntry>? {
        try {
            DiffFormatter(DisabledOutputStream.INSTANCE).use { df ->
                df.setBinaryFileThreshold(2 * 1024) // 2 mb max a file
                df.setRepository(repo)
                df.setDiffComparator(RawTextComparator.DEFAULT)
                df.isDetectRenames = true

                setContext(df)

                var diffs: List<DiffEntry>? = null
                if (parentCommit == null) {
                    RevWalk(repo).use { rw ->
                        val commit = rw.parseCommit(currentCommit)
                        diffs = df.scan(
                            EmptyTreeIterator(),
                            CanonicalTreeParser(null, rw.objectReader, commit.tree)
                        )
                    }
                } else {
                    diffs = df.scan(parentCommit, currentCommit)
                }
                return diffs
            }
        } catch (e: IOException) {
            throw RuntimeException("Error diffing ${parentCommit!!.name} and ${currentCommit.name} in $path", e)
        }
    }

    private fun setContext(df: DiffFormatter) {
        try {
            val context = getSystemProperty("git.diffcontext") /* TODO: make it into a configuration */
            df.setContext(context)
        } catch (e: Exception) {
            return
        }
    }

    private fun getSourceCode(repo: Repository, diff: DiffEntry): String {
        if (!collectConfig!!.isCollectingSourceCode) return ""

        try {
            val reader = repo.newObjectReader()
            val bytes = reader.open(diff.newId.toObjectId()).bytes
            return String(bytes, charset("utf-8"))
        } catch (e: Throwable) {
            return ""
        }
    }

    private fun getDiffText(repo: Repository, diff: DiffEntry): String {
        if (!collectConfig!!.isCollectingDiffs) return ""

        val out = ByteArrayOutputStream()
        try {
            DiffFormatter(out).use { df2 ->
                df2.setRepository(repo)
                df2.format(diff)
                val diffText = out.toString("UTF-8")
                return diffText
            }
        } catch (e: Throwable) {
            return ""
        }
    }

    @Synchronized
    override fun checkout(hash: String) {
        try {
            git.use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).call()
                git.checkout().setName(mainBranchName).call()
                deleteMMBranch(git)
                git.checkout().setCreateBranch(true).setName(BRANCH_MM).setStartPoint(hash).setForced(true)
                    .setOrphan(true).call()
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @Synchronized
    private fun deleteMMBranch(git: Git) {
        git.branchList().call().firstOrNull { it.name.endsWith(BRANCH_MM) }?.let {
            git.branchDelete().setBranchNames(BRANCH_MM).setForce(true).call()
        }
    }

    @Synchronized
    override fun files(): List<RepositoryFile> = allFilesInPath.map { RepositoryFile(it) }

    @Synchronized
    override fun reset() {
        safeCall({
            git.use { git ->
                git.checkout().setName(mainBranchName).setForced(true).call()
                git.branchDelete().setBranchNames(BRANCH_MM).setForce(true).call()
            }
        })
    }

    private val allFilesInPath: List<File> get() = RDFileUtils.getAllFilesInPath(path)

    override val totalCommits: Long get() = changeSets.size.toLong()

    override fun repositoryAllSize(): Map<String, CommitSize> = repositorySize(true, null, null)

    override fun repositorySize(filePath: String): Map<String, CommitSize> = repositorySize(false, null, filePath)

    override fun repositorySize(branchOrTag: String, filePath: String): Map<String, CommitSize> =
        repositorySize(false, branchOrTag, filePath)

    private fun repositorySize(all: Boolean, branchOrTag: String?, filePath: String?): Map<String, CommitSize> {
        val filePath = if (filePath == path) "" else filePath
        val localPath = if (filePath != null && filePath.startsWith(path)) filePath.substring(path.length + 1)
            .replace("\\", "/") else ""
        return dataBaseUtil!!.getCommitSizeMap(projectId!!, localPath)
    }

    fun blame(file: String, commitToBeBlamed: String): List<BlamedLine> {
        return blame(file, commitToBeBlamed, true)
    }

    override fun blame(file: String): List<BlamedLine> {
        try {
            git.use { git ->
                val blameResult = git.blame().setFilePath(file.replace("\\", "/")).setFollowFileRenames(true).call()
                if (blameResult != null) {
                    val rows = blameResult.resultContents.size()
                    val result: MutableList<BlamedLine> = ArrayList()
                    for (i in 0..<rows) {
                        result.add(
                            BlamedLine(
                                i,
                                blameResult.resultContents.getString(i),
                                blameResult.getSourceAuthor(i).name,
                                blameResult.getSourceCommitter(i).name,
                                blameResult.getSourceCommit(i).id.name
                            )
                        )
                    }

                    return result
                } else {
                    // TODO create notification
                    println("BlameResult not found. File: $file")
                    return ArrayList()
                    //				throw new RuntimeException("BlameResult not found. File: " + file);
                }
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun blameManager(): BlameManager {
        try {
            git.use { git ->
                val fileMap: MutableMap<String, BlameFileInfo> = HashMap()
                for (file in files()) {
                    val localFilePath = file.file.path.substring(path!!.length + 1).replace("\\", "/")
                    val blameResult = git.blame().setFilePath(localFilePath).setFollowFileRenames(true).call()

                    if (blameResult != null) {
                        val rows = blameResult.resultContents.size()
                        for (i in 0..<rows) {
                            val author = blameResult.getSourceAuthor(i).name
                            val fileName = blameResult.getSourcePath(i)
                            val commit = blameResult.getSourceCommit(i)
                            val blameAuthorInfo = BlameAuthorInfo(
                                author,
                                mutableSetOf(commit),
                                1,
                                blameResult.resultContents.getString(i).toByteArray().size.toLong()
                            )
                            fileMap.computeIfAbsent(blameResult.getSourcePath(i)) { k: String? -> BlameFileInfo(fileName) }
                                .add(blameAuthorInfo)
                        }
                    } else {
                        // TODO create notification
                        println("BlameResult not found. File: $file localFilePath: $localFilePath")
                        //	throw new RuntimeException("BlameResult not found. File: " + file + " localFilePath: " + localFilePath);
                    }
                }
                return BlameManager(fileMap, repoName)
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun blame(file: String, commitToBeBlamed: String, priorCommit: Boolean): List<BlamedLine> {
        try {
            git.use { git ->
                val gitCommitToBeBlamed: ObjectId
                if (priorCommit) {
                    val commits = git.log().add(git.repository.resolve(commitToBeBlamed)).call()
                    gitCommitToBeBlamed = commits.iterator().next().getParent(0).id
                } else {
                    gitCommitToBeBlamed = git.repository.resolve(commitToBeBlamed)
                }

                val blameResult = git.blame().setFilePath(file.replace("\\", "/")).setStartCommit(gitCommitToBeBlamed)
                    .setFollowFileRenames(true).call()
                if (blameResult != null) {
                    val rows = blameResult.resultContents.size()
                    val result: MutableList<BlamedLine> = ArrayList()
                    for (i in 0..<rows) {
                        result.add(
                            BlamedLine(
                                i,
                                blameResult.resultContents.getString(i),
                                blameResult.getSourceAuthor(i).name,
                                blameResult.getSourceCommitter(i).name,
                                blameResult.getSourceCommit(i).id.name
                            )
                        )
                    }

                    return result
                } else {
                    // TODO create notification
                    println("BlameResult not found. File: $file")
                    return ArrayList()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override val developerInfo get() = getDeveloperInfo(null)

    override fun dbPrepared() {
        developersMap.clear()
        val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val futures: MutableList<Future<*>> = ArrayList()
        try {
            git.use { git ->
                val commits = StreamSupport.stream(git.log().call().spliterator(), true)
                    .filter { commit: RevCommit -> !dataBaseUtil!!.isCommitExist(commit.name) }
                    .toList()
                val authorIdCache = ConcurrentHashMap<String, Long>()

                for (i in commits.indices) {
                    val commit = commits[i]

                    val future = executor.submit {
                        try {
                            val dataBaseUtil1 = DataBaseUtil(dataBaseUtil!!.url)
                            val fileList: MutableList<org.tera201.vcsmanager.scm.entities.FileEntity> =
                                ArrayList()
                            val author = commit.authorIdent
                            val authorId = authorIdCache.computeIfAbsent(author.emailAddress) { e: String? ->
                                val id = dataBaseUtil!!.getAuthorId(projectId!!, e!!)
                                id ?: dataBaseUtil1.insertAuthor(projectId!!, author.name, e)!!
                            }
                            val paths = GitRepositoryUtil.getCommitsFiles(commit, git)
                            paths.keys.forEach(Consumer { it: String ->
                                filePathMap.computeIfAbsent(it) { k: String? ->
                                    val id = dataBaseUtil!!.getFilePathId(projectId!!, k!!)
                                    id ?: dataBaseUtil1.insertFilePath(projectId!!, k)!!
                                }
                            })
                            val commitStability =
                                CommitStabilityAnalyzer.analyzeCommit(git, commits, commit, commits.indexOf(commit))
                            val commitSize = GitRepositoryUtil.processCommitSize(commit, git)
                            val fileMergedEntity = paths.values.stream().reduce(
                                FileEntity()
                            ) { acc: FileEntity, fileEntity: FileEntity? ->
                                acc.add(
                                    fileEntity!!
                                )
                                acc
                            }
                            dataBaseUtil1.insertCommit(
                                projectId!!,
                                authorId,
                                commit.name,
                                commit.commitTime,
                                commitSize,
                                commitStability,
                                fileMergedEntity
                            )
                            paths.keys.forEach(Consumer { it: String ->
                                fileList.add(
                                    org.tera201.vcsmanager.scm.entities.FileEntity(
                                        projectId!!,
                                        it,
                                        filePathMap[it]!!,
                                        commit.name,
                                        commit.commitTime
                                    )
                                )
                            })
                            dataBaseUtil1.insertFile(fileList)
                            dataBaseUtil1.closeConnection()
                        } catch (e: Exception) {
                            System.err.println("Error processing commit " + commit.name + ": " + e.message)
                        }
                    }
                    futures.add(future)
                }
                for (f in futures) {
                    f.get()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        } finally {
            executor.shutdown()
        }
    }

    override fun getDeveloperInfo(nodePath: String?): Map<String, DeveloperInfo> {
        return runBlocking {
            git.use { git ->
                val localPath = nodePath?.takeIf { it != path && it.startsWith(path!!) }
                    ?.substring(path.length + 1)?.replace("\\", "/")
                val commits = if (localPath != null) git.log().addPath(localPath).call() else git.log().call()

                val commitJobs = commits.map { commit ->
                    async {
                        val dbAsync = DataBaseUtil(dataBaseUtil!!.url)
                        dbAsync.getCommit(projectId!!, commit.name)?.let { commitEntity ->
                            developersMap.computeIfAbsent(commitEntity.authorEmail) { DeveloperInfo(commitEntity, commit) }
                                .updateByCommit(commitEntity, commit)
                        }
                        dbAsync.closeConnection()

                    }
                }
                commitJobs.awaitAll()

                val head = git.repository.resolve(Constants.HEAD)
                val fileStream = files()
                    .filter {  (it.file.path.startsWith(localPath ?: "") && !it.file.path.endsWith(".DS_Store")) }
                    .map { it.file.path.substring(path.length + 1).replace("\\", "/") }
                    .filter { filePathMap.keys.contains(it) }
                val fileHashes =
                    fileStream.map { it to head.name }
                        .filter { dataBaseUtil!!.getBlameFileId(projectId!!, filePathMap[it.first]!!, it.second!!) == null }
                val fileAndBlameHashes = fileHashes.map { it.first to dataBaseUtil!!.insertBlameFile(projectId!!, filePathMap[it.first]!!, it.second!!) }

                val devs = dataBaseUtil!!.getDevelopersByProjectId(projectId!!)

                val fileProcessingJobs = fileAndBlameHashes.toSet().map { (filePath, blameId) ->
                    async(Dispatchers.IO) {
                        val dbAsync = DataBaseUtil(dataBaseUtil!!.url)
                        try {
                            git.blame().setFilePath(filePath).setStartCommit(head).call()?.let {
                                GitRepositoryUtil
                                    .updateFileOwner( it, devs, dbAsync, projectId!!, blameId, head.name)
                            }
                            dbAsync.updateBlameFileSize(blameId)
                        } catch (e: GitAPIException) {
                            e.printStackTrace()
                        }
                        dbAsync.closeConnection()

                    }
                }
                fileProcessingJobs.awaitAll()

                dataBaseUtil!!.developerUpdateByBlameInfo(projectId!!, developersMap)
            }
            developersMap
        }
    }

    private fun processDeveloperInfo(
        commit: RevCommit,
        git: Git,
        developers: ConcurrentHashMap<String, DeveloperInfo>
    ) {
        try {
            val email = commit.authorIdent.emailAddress
            val dev = developers.computeIfAbsent(email) { k: String? ->
                DeveloperInfo(name = commit.authorIdent.name, emailAddress = email)
            }
            dev.commits.add(commit)
            GitRepositoryUtil.analyzeCommit(commit, git, dev)
        } catch (ignored: IOException) {
        }
    }

    override fun getCommitFromTag(tag: String): String = safeCall({
        git.use { git ->
            val commits = git.log().add(getActualRefObjectId(git.repository.findRef(tag), git.repository)).call()
            return commits.first().name
        }
    }, "Failed for tag $tag")

    private fun getActualRefObjectId(ref: Ref, repo: Repository): ObjectId {
        val peeledId = repo.refDatabase.peel(ref).peeledObjectId
        return peeledId ?: ref.objectId
    }

    /**
     * Returns the max number of files in a commit, defaulting to a large value.
     * Can be overridden with the "git.maxfiles" environment variable.
     */
    private fun checkMaxNumberOfFiles(): Int =
        runCatching { getSystemProperty("git.maxfiles") }.getOrDefault(DEFAULT_MAX_NUMBER_OF_FILES_IN_A_COMMIT)


    /**
     * Returns the max diff size in bytes, defaulting to a large value.
     * Can be overridden with the "git.maxdiff" environment variable.
     */
    private fun checkMaxSizeOfDiff(): Int =
        runCatching { getSystemProperty("git.maxdiff") }.getOrDefault(MAX_SIZE_OF_A_DIFF)

    /** Get this system property (environment variable)'s value as an integer. */
    private fun getSystemProperty(name: String): Int {
        val `val` = System.getProperty(name)
        return `val`.toInt()
    }

    fun setPath(path: String) {
        this.path = PathUtils.fullPath(path)
    }

    override fun clone(dest: Path): SCM {
        log.info("Cloning to $dest")
        RDFileUtils.copyDirTree(Paths.get(path), dest)
        return GitRepository(dest.toString())
    }

    override fun delete() {
        // allow to be destroyed more than once
        if (RDFileUtils.exists(Paths.get(path))) {
            log.info("Deleting: $path")
            try {
                FileUtils.deleteDirectory(File(path))
            } catch (e: IOException) {
                log.info("Delete failed: $e")
            }
        }
    }

    override fun setDataToCollect(config: CollectConfiguration) {
        this.collectConfig = config
    }

    /** True if all filters accept, else false. */
    private fun diffFiltersAccept(diff: DiffEntry): Boolean =
        collectConfig!!.diffFilters.any { !it.accept(diff.newPath) }.not()

    private inline fun <T> safeCall(action: () -> T, errorMessage: String? = null): T {
        return runCatching { action() }.getOrElse { throw RuntimeException(errorMessage, it) }
    }

    companion object {
        /* Constants. */
        private const val MAX_SIZE_OF_A_DIFF = 100000
        private const val DEFAULT_MAX_NUMBER_OF_FILES_IN_A_COMMIT = 5000
        private const val BRANCH_MM = "mm" /* TODO mm -> rd. */
        private val log: Logger = LoggerFactory.getLogger(GitRepository::class.java)
        private val developersMap = ConcurrentHashMap<String, DeveloperInfo>()
        private val filePathMap = ConcurrentHashMap<String, Long>()

        fun singleProject(path: String): SCMRepository = GitRepository(path).info

        fun singleProject(path: String, singleParentOnly: Boolean, dataBaseUtil: DataBaseUtil?): SCMRepository =
            GitRepository(path, singleParentOnly, dataBaseUtil).info

        fun allProjectsIn(path: String?, singleParentOnly: Boolean = false): Array<SCMRepository> =
            RDFileUtils.getAllDirsIn(path).map { singleProject(it, singleParentOnly, null) }.toTypedArray()

    }
}
