package org.tera201.vcsmanager.scm

import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.blame.BlameResult
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
import org.tera201.vcsmanager.util.*
import org.tera201.vcsmanager.util.FileEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.StreamSupport

/* TODO Name: Sounds like it inherits SCMRepository, but it actually implements SCM. */
open class GitRepository : SCM {
    /* Auto-determined. */
    private var mainBranchName: String? = null
    var maxNumberFilesInACommit: Int = -1 /* TODO Expose an API to control this value? Also in SubversionRepository. */
        private set
    private var maxSizeOfDiff = -1 /* TODO Expose an API to control this value? Also in SubversionRepository. */

    private var collectConfig: CollectConfiguration? = null

    private var repoName: String? = null

    /* User-specified. */
    private var path: String? = null
    private var firstParentOnly = false
    var sizeCache: Map<ObjectId, Long> = ConcurrentHashMap()
    @JvmField
	protected var dataBaseUtil: DataBaseUtil? = null
    @JvmField
	protected var projectId: Int? = null

    /**
     * Intended for sub-classes.
     * Make sure you initialize appropriately with the Setters.
     */
    protected constructor() : this(null)

    @JvmOverloads
    constructor(path: String?, firstParentOnly: Boolean = false) {
        log.debug("Creating a GitRepository from path $path")
        setPath(path)
        setFirstParentOnly(firstParentOnly)
        maxNumberFilesInACommit = checkMaxNumberOfFiles()
        maxSizeOfDiff = checkMaxSizeOfDiff()

        this.collectConfig = CollectConfiguration().everything()
    }

    constructor(path: String, firstParentOnly: Boolean, dataBaseUtil: DataBaseUtil?) {
        log.debug("Creating a GitRepository from path $path")
        this.dataBaseUtil = dataBaseUtil
        val splitPath = path.replace("\\", "/").split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        setRepoName(splitPath[splitPath.size - 1])
        val projectId = dataBaseUtil?.getProjectId(repoName!!, path)
        this.projectId = Objects.requireNonNullElseGet(
            projectId
        ) { dataBaseUtil?.insertProject(repoName!!, path) }
        setPath(path)
        setFirstParentOnly(firstParentOnly)

        maxNumberFilesInACommit = checkMaxNumberOfFiles()
        maxSizeOfDiff = checkMaxSizeOfDiff()

        this.collectConfig = CollectConfiguration().everything()
    }

    override fun info(): SCMRepository {
        try {
            openRepository().use { git ->
                RevWalk(git.repository).use { rw ->
                    val headId: AnyObjectId = git.repository.resolve(Constants.HEAD)
                    val root = rw.parseCommit(headId)
                    rw.sort(RevSort.REVERSE)
                    rw.markStart(root)
                    val lastCommit = rw.next()

                    val origin = git.repository.config.getString("remote", "origin", "url")
                    return SCMRepository(this, origin, repoName, path, headId.name, lastCommit.name)
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Couldn't create JGit instance with path $path")
        }
    }

    val info: SCMRepository
        get() {
            try {
                openRepository().use { git ->
                    val head =
                        git.repository.resolve(Constants.HEAD)
                    val rw = RevWalk(git.repository)
                    val root = rw.parseCommit(head)
                    rw.sort(RevSort.REVERSE)
                    rw.markStart(root)
                    val lastCommit = rw.next()
                    val origin = git.repository.config.getString("remote", "origin", "url")

                    repoName = if (origin != null) GitRemoteRepository.repoNameFromURI(origin) else repoName
                    return SCMRepository(
                        this,
                        origin,
                        repoName,
                        path,
                        head.name,
                        lastCommit.name
                    )
                }
            } catch (e: Exception) {
                throw RuntimeException("Couldn't create JGit instance with path $path")
            }
        }

    @Throws(IOException::class, GitAPIException::class)
    fun openRepository(): Git = Git.open(File(path)).also { git ->
        this.mainBranchName = this.mainBranchName ?: discoverMainBranchName(git)
    }

    @Throws(IOException::class)
    private fun discoverMainBranchName(git: Git): String = git.repository.branch

    override val head: ChangeSet get() {
        var revWalk: RevWalk? = null
        try {
            openRepository().use { git ->
                val head = git.repository.resolve(Constants.HEAD)
                revWalk = RevWalk(git.repository)
                val r = revWalk!!.parseCommit(head)
                git.close()
                return ChangeSet(r.name, convertToDate(r))
            }
        } catch (e: Exception) {
            throw RuntimeException("error in getHead() for $path", e)
        } finally {
            revWalk!!.close()
        }
    }

    override val changeSets: List<ChangeSet> get() {
        try {
            openRepository().use { git ->
                val allCs = if (!firstParentOnly) getAllCommits(git)
                else firstParentsOnly(git)
                git.close()
                return allCs
            }
        } catch (e: Exception) {
            throw RuntimeException("error in getChangeSets for $path", e)
        }
    }

    override fun createCommit(message: String) {
        try {
            openRepository().use { git ->
                val status = git.status().call()
                if (status.hasUncommittedChanges()) {
                    val add = git.add()
                    for (entry in status.modified) {
                        add.addFilepattern(entry)
                    }
                    add.call()
                    git.commit().setMessage(message).call()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("error in create commit for $path", e)
        }
    }

    override fun resetLastCommitsWithMessage(message: String) {
        try {
            openRepository().use { git ->
                var commit: RevCommit? = null
                for (r in git.log().call()) {
                    if (!r.fullMessage.contains(message)) {
                        commit = r
                        break
                    }
                }
                if (commit != null) {
                    git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(extractChangeSet(commit).id).call()
                } else {
                    log.info("Reset doesn't required")
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Reset failed ", e)
        }
    }

    private fun firstParentsOnly(git: Git): List<ChangeSet> {
        var revWalk: RevWalk? = null
        try {
            val allCs: MutableList<ChangeSet> = ArrayList()

            revWalk = RevWalk(git.repository)
            revWalk.revFilter = FirstParentFilter()
            revWalk.sort(RevSort.TOPO)
            val headRef = git.repository.findRef(Constants.HEAD)
            val headCommit = revWalk.parseCommit(headRef.objectId)
            revWalk.markStart(headCommit)
            for (revCommit in revWalk) {
                allCs.add(extractChangeSet(revCommit))
            }

            return allCs
        } catch (e: Exception) {
            throw RuntimeException(e)
        } finally {
            revWalk!!.close()
        }
    }

    @Throws(GitAPIException::class, IOException::class)
    private fun getAllCommits(git: Git): List<ChangeSet> {
        val allCs: MutableList<ChangeSet> = ArrayList()

        for (r in git.log().call()) {
            allCs.add(extractChangeSet(r))
        }
        return allCs
    }

    private fun extractChangeSet(r: RevCommit): ChangeSet {
        val hash = r.name
        val date = convertToDate(r)

        return ChangeSet(hash, date)
    }

    private fun convertToDate(revCommit: RevCommit): GregorianCalendar {
        val date = GregorianCalendar()
        date.timeZone = revCommit.authorIdent.timeZone
        date.time = revCommit.authorIdent.getWhen()

        return date
    }

    /**
     * Get the commit with this commit id.
     * Caveats:
     * - If commit modifies more than maxNumberFilesInACommit, throws an exception
     * - If one of the file diffs exceeds maxSizeOfDiff, the diffText is discarded
     *
     * @param id    The SHA1 hash that identifies a git commit.
     * @returns Commit 	The corresponding Commit, or null.
     */
    override fun getCommit(id: String): Commit? {
        try {
            openRepository().use { git ->
                /* Using JGit, this commit will be the first entry in the log beginning at id. */
                val repo = git.repository
                val jgitCommits = git.log().add(repo.resolve(id)).call()
                val itr: Iterator<RevCommit> = jgitCommits.iterator()

                if (!itr.hasNext()) return null
                val jgitCommit = itr.next()

                /* Extract metadata. */
                val author = Developer(jgitCommit.authorIdent.name, jgitCommit.authorIdent.emailAddress)
                val committer = Developer(jgitCommit.committerIdent.name, jgitCommit.committerIdent.emailAddress)
                val authorTimeZone = jgitCommit.authorIdent.timeZone
                val committerTimeZone = jgitCommit.committerIdent.timeZone

                val msg =
                    if (collectConfig!!.isCollectingCommitMessages) jgitCommit.fullMessage.trim { it <= ' ' } else ""
                val hash = jgitCommit.name.toString()
                val parents = Arrays.stream(jgitCommit.parents)
                    .map { rc: RevCommit -> rc.name.toString() }.collect(Collectors.toList())

                val authorDate = GregorianCalendar()
                authorDate.time = jgitCommit.authorIdent.getWhen()
                authorDate.timeZone = jgitCommit.authorIdent.timeZone

                val committerDate = GregorianCalendar()
                committerDate.time = jgitCommit.committerIdent.getWhen()
                committerDate.timeZone = jgitCommit.committerIdent.timeZone

                val isMerge = (jgitCommit.parentCount > 1)

                val branches = getBranches(git, hash)
                val isCommitInMainBranch = branches.contains(this.mainBranchName)

                /* Create one of our Commit's based on the jgitCommit metadata. */
                val commit = Commit(
                    hash,
                    author,
                    committer,
                    authorDate,
                    authorTimeZone,
                    committerDate,
                    committerTimeZone,
                    msg,
                    parents,
                    isMerge,
                    branches,
                    isCommitInMainBranch
                )

                /* Convert each of the associated DiffEntry's to a Modification. */
                val diffsForTheCommit = diffsForTheCommit(repo, jgitCommit)
                if (diffsForTheCommit!!.size > maxNumberFilesInACommit) {
                    val errMsg = "Commit $id touches more than $maxNumberFilesInACommit files"
                    log.error(errMsg)
                    throw RepoDrillerException(errMsg)
                }

                for (diff in diffsForTheCommit) {
                    if (this.diffFiltersAccept(diff)) {
                        val m = this.diffToModification(repo, diff)
                        commit.addModification(m)
                    }
                }

                git.close()
                return commit
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("error detailing $id in $path", e)
        }
    }

    @Throws(GitAPIException::class)
    private fun getBranches(git: Git, hash: String): Set<String?> {
        if (!collectConfig!!.isCollectingBranches) return HashSet()

        val gitBranches = git.branchList().setContains(hash).call()
        return gitBranches.stream()
            .map { ref: Ref -> ref.name.substring(ref.name.lastIndexOf("/") + 1) }
            .collect(Collectors.toSet())
    }

    override val allBranches: List<Ref> get() {
        try {
            openRepository().use { git ->
                return git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()
            }
        } catch (e: Exception) {
            throw RuntimeException(
                "error getting branches in $path",
                e
            )
        }
    }

    override val allTags: List<Ref> get() {
        try {
            openRepository().use { git ->
                return git.tagList().call()
            }
        } catch (e: Exception) {
            throw RuntimeException(
                "error getting tags in $path",
                e
            )
        }
    }

    @Throws(CheckoutException::class)
    override fun checkoutTo(branch: String) {
        try {
            openRepository().use { git ->
                if (git.repository.isBare) throw CheckoutException("error repo is bare")
                if (git.repository.findRef(branch) == null) {
                    throw CheckoutException("Branch does not exist: $branch")
                }

                val status = git.status().call()
                if (status.hasUncommittedChanges()) {
                    throw CheckoutException("There are uncommitted changes in the working directory")
                }
                git.checkout().setName(branch).call()
            }
        } catch (e: IOException) {
            throw CheckoutException(
                "Error checking out to $branch",
                e
            )
        } catch (e: GitAPIException) {
            throw CheckoutException(
                "Error checking out to $branch",
                e
            )
        }
    }

    override val currentBranchOrTagName: String
        get() {
            try {
                openRepository().use { git ->
                    val head = git.repository.resolve("HEAD")
                    return git.repository.allRefsByPeeledObjectId[head]!!.stream()
                        .map { obj: Ref -> obj.name }
                        .distinct().filter { it: String -> it.startsWith("refs/") }.findFirst().orElse(head.name)
                }
            } catch (e: Exception) {
                throw RuntimeException("Error getting branch name", e)
            }
    }

    @Throws(IOException::class)
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

    @Throws(IOException::class)
    private fun diffsForTheCommit(repo: Repository, commit: RevCommit): List<DiffEntry>? {
        val currentCommit: AnyObjectId = repo.resolve(commit.name)
        val parentCommit: AnyObjectId? = if (commit.parentCount > 0) repo.resolve(commit.getParent(0).name) else null

        return this.getDiffBetweenCommits(repo, parentCommit, currentCommit)
    }

    override fun getDiffBetweenCommits(priorCommitHash: String, laterCommitHash: String): List<Modification> {
        try {
            openRepository().use { git ->
                val repo = git.repository
                val priorCommit: AnyObjectId = repo.resolve(priorCommitHash)
                val laterCommit: AnyObjectId = repo.resolve(laterCommitHash)

                val diffs = this.getDiffBetweenCommits(repo, priorCommit, laterCommit)
                val modifications = diffs!!.stream()
                    .map { diff: DiffEntry ->
                        try {
                            return@map this.diffToModification(repo, diff)
                        } catch (e: IOException) {
                            throw RuntimeException("error diffing $priorCommitHash and $laterCommitHash in $path", e)
                        }
                    }
                    .collect(Collectors.toList())
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
            throw RuntimeException(
                "error diffing " + parentCommit!!.name + " and " + currentCommit.name + " in " + path, e
            )
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

    @Throws(IOException::class)
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

    @Throws(IOException::class)
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
            openRepository().use { git ->
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
    @Throws(GitAPIException::class)
    private fun deleteMMBranch(git: Git) {
        val refs = git.branchList().call()
        for (r in refs) {
            if (r.name.endsWith(BRANCH_MM)) {
                git.branchDelete().setBranchNames(BRANCH_MM).setForce(true).call()
                break
            }
        }
    }

    @Synchronized
    override fun files(): List<RepositoryFile> {
        val all: MutableList<RepositoryFile> = ArrayList()
        for (f in allFilesInPath) {
            all.add(RepositoryFile(f))
        }

        return all
    }

    @Synchronized
    override fun reset() {
        try {
            openRepository().use { git ->
                git.checkout().setName(mainBranchName).setForced(true).call()
                git.branchDelete().setBranchNames(BRANCH_MM).setForce(true).call()
            }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private val allFilesInPath: List<File>
        get() = RDFileUtils.getAllFilesInPath(path)

    override fun totalCommits(): Long {
        return changeSets.size.toLong()
    }

    override fun repositoryAllSize(): Map<String, CommitSize> {
        return repositorySize(true, null, null)
    }

    override fun repositorySize(filePath: String): Map<String, CommitSize> {
        return repositorySize(false, null, filePath)
    }

    override fun repositorySize(branchOrTag: String, filePath: String): Map<String, CommitSize> {
        return repositorySize(false, branchOrTag, filePath)
    }

    private fun repositorySize(all: Boolean, branchOrTag: String?, filePath: String?): Map<String, CommitSize> {
        var filePath = filePath
        filePath = if (filePath == path) "" else filePath
        val localPath = if (filePath != null && filePath.startsWith(path!!)) filePath.substring(path!!.length + 1)
            .replace("\\", "/") else ""
        return dataBaseUtil!!.getCommitSizeMap(projectId!!, localPath)
    }

    fun blame(file: String, commitToBeBlamed: String): List<BlamedLine> {
        return blame(file, commitToBeBlamed, true)
    }

    override fun blame(file: String): List<BlamedLine> {
        try {
            openRepository().use { git ->
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
            openRepository().use { git ->
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
                                setOf(commit),
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
            openRepository().use { git ->
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

    @get:Throws(IOException::class, GitAPIException::class)
    override val developerInfo = getDeveloperInfo(null)

    override fun dbPrepared() {
        developersMap.clear()
        val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val futures: MutableList<Future<*>> = ArrayList()
        try {
            openRepository().use { git ->
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
                                        filePathMap[it],
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

    @Throws(IOException::class, GitAPIException::class)
    override fun getDeveloperInfo(nodePath: String?): Map<String, DeveloperInfo> {
        var nodePath = nodePath
        openRepository().use { git ->
            nodePath = if (nodePath == path) null else nodePath
            val localPath =
                if (nodePath != null && nodePath!!.startsWith(path!!)) nodePath!!.substring(path!!.length + 1)
                    .replace("\\", "/") else nodePath
            val commits = if (localPath != null) git.log().addPath(localPath).call() else git.log().call()
            val executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
            val futures: MutableList<Future<*>> = ArrayList()

            for (commit in commits) {
                // Submitting tasks to the thread pool
                val future = executorService.submit {
                    val dataBaseUtil1 = DataBaseUtil(dataBaseUtil!!.url)
                    val commitEntity = dataBaseUtil1.getCommit(projectId!!, commit.name)
                    val dev =
                        developersMap.computeIfAbsent(commitEntity!!.authorEmail) { k: String? ->
                            DeveloperInfo(
                                commitEntity,
                                commit
                            )
                        }
                    dev.updateByCommit(commitEntity, commit)
                    dataBaseUtil1.closeConnection()
                }
                futures.add(future)
            }

            for (future in futures) {
                try {
                    future.get() // Catch exceptions if they occur during task execution
                } catch (e: InterruptedException) {
                    e.printStackTrace() // Logging or other error handling
                } catch (e: ExecutionException) {
                    e.printStackTrace()
                }
            }

            futures.clear()

            val head = git.repository.resolve(Constants.HEAD)
            val finalNodePath = nodePath
            val fileStream = files().stream().parallel().filter { it: RepositoryFile ->
                ((finalNodePath == null || it.file.path.startsWith(finalNodePath)) && !it.file.path.endsWith(".DS_Store"))
            }
                .map { it: RepositoryFile -> it.file.path.substring(path!!.length + 1).replace("\\", "/") }
                .filter { o: String -> filePathMap.keys.contains(o) }
            val fileHashes = fileStream.map { it: String -> Pair(it, head.name) }.filter { it: Pair<String, String?> ->
                dataBaseUtil!!.getBlameFileId(
                    projectId!!, filePathMap[it.first]!!, it.second!!
                ) == null
            }
            val fileAndBlameHashes = fileHashes.map { it: Pair<String, String?> ->
                Pair(
                    it.first, dataBaseUtil!!.insertBlameFile(
                        projectId!!, filePathMap[it.first]!!, it.second!!
                    )
                )
            }
            val devs = dataBaseUtil!!.getDevelopersByProjectId(
                projectId!!
            )

            for ((first, second) in fileAndBlameHashes.collect(Collectors.toSet<Pair<String, Int>>())) {
                val future = executorService.submit {
                    val dataBaseUtil1 = DataBaseUtil(dataBaseUtil!!.url)
                    val blameResult: BlameResult?
                    try {
                        blameResult = git.blame().setFilePath(first).setStartCommit(head).call()
                    } catch (e: GitAPIException) {
                        throw RuntimeException(e)
                    }
                    if (blameResult != null) {
                        GitRepositoryUtil.updateFileOwnerBasedOnBlame(
                            blameResult,
                            devs,
                            dataBaseUtil1,
                            projectId,
                            second,
                            head.name
                        )
                        dataBaseUtil1.updateBlameFileSize(second)
                        dataBaseUtil1.closeConnection()
                    }
                }
                futures.add(future)
            }
            for (future in futures) {
                try {
                    future.get() // Catch exceptions if they occur during task execution
                } catch (e: InterruptedException) {
                    e.printStackTrace() // Logging or other error handling
                } catch (e: ExecutionException) {
                    e.printStackTrace()
                }
            }
            dataBaseUtil!!.developerUpdateByBlameInfo(projectId!!, developersMap)
            executorService.shutdown()
        }
        return developersMap
    }

    private fun processDeveloperInfo(
        commit: RevCommit,
        git: Git,
        developers: ConcurrentHashMap<String, DeveloperInfo>
    ) {
        try {
            val email = commit.authorIdent.emailAddress
            val dev = developers.computeIfAbsent(email) { k: String? -> DeveloperInfo(commit.authorIdent.name, email) }
            dev.addCommit(commit)
            GitRepositoryUtil.analyzeCommit(commit, git, dev)
        } catch (ignored: IOException) {
        }
    }

    override fun getCommitFromTag(tag: String): String {
        try {
            openRepository().use { git ->
                val repo = git.repository
                val commits = git.log().add(getActualRefObjectId(repo.findRef(tag), repo)).call()
                git.close()
                for (commit in commits) {
                    return commit.name.toString()
                }

                throw RuntimeException("Failed for tag $tag") // we never arrive here, hopefully
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed for tag $tag", e)
        }
    }

    @Throws(IOException::class)
    private fun getActualRefObjectId(ref: Ref, repo: Repository): ObjectId {
        val repoPeeled = repo.refDatabase.peel(ref)
        if (repoPeeled.peeledObjectId != null) {
            return repoPeeled.peeledObjectId
        }
        return ref.objectId
    }

    /**
     * Return the max number of files in a commit.
     * Default is hard-coded to "something large".
     * Override with environment variable "git.maxfiles".
     *
     * @return Max number of files in a commit
     */
    private fun checkMaxNumberOfFiles(): Int {
        return try {
            getSystemProperty("git.maxfiles")
        } catch (e: Exception) {
            DEFAULT_MAX_NUMBER_OF_FILES_IN_A_COMMIT
        }
    }

    /**
     * Return the max size of a diff in bytes.
     * Default is hard-coded to "something large".
     * Override with environment variable "git.maxdiff".
     *
     * @return Max diff size
     */
    private fun checkMaxSizeOfDiff(): Int {
        return try {
            getSystemProperty("git.maxdiff")
        } catch (e: Exception) {
            MAX_SIZE_OF_A_DIFF
        }
    }

    /**
     * Get this system property (environment variable)'s value as an integer.
     *
     * @param name    Environment variable to retrieve
     * @return    `name` successfully parsed as an int
     * @throws NumberFormatException
     */
    @Throws(NumberFormatException::class)
    private fun getSystemProperty(name: String): Int {
        val `val` = System.getProperty(name)
        return `val`.toInt()
    }

    fun setRepoName(repoName: String?) {
        this.repoName = repoName
    }

    fun setPath(path: String?) {
        this.path = PathUtils.fullPath(path)
    }

    fun setFirstParentOnly(firstParentOnly: Boolean) {
        this.firstParentOnly = firstParentOnly
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
                FileUtils.deleteDirectory(File(path.toString()))
            } catch (e: IOException) {
                log.info("Delete failed: $e")
            }
        }
    }

    override fun setDataToCollect(config: CollectConfiguration) {
        this.collectConfig = config
    }

    /**
     * True if all filters accept, else false.
     *
     * @param diff    DiffEntry to evaluate
     * @return allAccepted
     */
    private fun diffFiltersAccept(diff: DiffEntry): Boolean {
        val diffFilters = collectConfig!!.diffFilters
        for (diffFilter in diffFilters) {
            if (!diffFilter.accept(diff.newPath)) {
                return false
            }
        }

        return true
    }

    companion object {
        /* Constants. */
        private const val MAX_SIZE_OF_A_DIFF = 100000
        private const val DEFAULT_MAX_NUMBER_OF_FILES_IN_A_COMMIT = 5000
        private const val BRANCH_MM = "mm" /* TODO mm -> rd. */

        private val log: Logger = LoggerFactory.getLogger(GitRepository::class.java)

        private val developersMap = ConcurrentHashMap<String, DeveloperInfo>()
        private val filePathMap = ConcurrentHashMap<String, Long>()

        fun singleProject(path: String?): SCMRepository {
            return GitRepository(path).info()
        }

        fun singleProject(path: String, singleParentOnly: Boolean, dataBaseUtil: DataBaseUtil?): SCMRepository {
            return GitRepository(path, singleParentOnly, dataBaseUtil).info()
        }

        @JvmOverloads
        fun allProjectsIn(path: String?, singleParentOnly: Boolean = false): Array<SCMRepository> {
            val repos: MutableList<SCMRepository> = ArrayList()

            for (dir in RDFileUtils.getAllDirsIn(path)) {
                repos.add(singleProject(dir, singleParentOnly, null))
            }

            return repos.toTypedArray<SCMRepository>()
        }
    }
}
