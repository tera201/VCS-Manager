package org.tera201.vcsmanager.scm

import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
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
import org.tera201.vcsmanager.VCSException
import org.tera201.vcsmanager.domain.*
import org.tera201.vcsmanager.scm.entities.*
import org.tera201.vcsmanager.scm.exceptions.CheckoutException
import org.tera201.vcsmanager.util.VCSDataBase
import org.tera201.vcsmanager.util.RDFileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

/* TODO Name: Sounds like it inherits SCMRepository, but it actually implements SCM. */
open class GitRepository : SCM {
    private var mainBranchName: String? = null
    var maxNumberFilesInACommit: Int = -1 /* TODO Expose an API to control this value? Also in SubversionRepository. */
        private set
    private var maxSizeOfDiff = -1 /* TODO Expose an API to control this value? Also in SubversionRepository. */
    private var collectConfig: CollectConfiguration? = null
    private var repoName: String
    var path: String
    protected var firstParentOnly = false
    var sizeCache: Map<ObjectId, Long> = ConcurrentHashMap()
    protected open var vcsDataBase: VCSDataBase? = null
    protected open var projectId: Int? = null
    override val changeSets: List<ChangeSet>
        get() = kotlin.runCatching {
            git.use { git -> return if (!firstParentOnly) getAllCommits(git) else firstParentsOnly(git) }
        }.getOrElse { throw RuntimeException("error in getChangeSets for $path", it) }

    /** Constructor, initializes the repository with given path and options */
    protected constructor() : this("")

    constructor(path: String, firstParentOnly: Boolean = false, vcsDataBase: VCSDataBase? = null) {
        log.debug("Creating a GitRepository from path $path")
        this.vcsDataBase = vcsDataBase
        this.path = path
        val splitPath = path.replace("\\", "/").split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        repoName = if (splitPath.isNotEmpty()) splitPath.last() else ""
        val projectId = vcsDataBase?.getProjectId(repoName, path) ?: vcsDataBase?.insertProject(repoName, path)
        this.firstParentOnly = firstParentOnly
        maxNumberFilesInACommit = checkMaxNumberOfFiles()
        maxSizeOfDiff = checkMaxSizeOfDiff()
        this.collectConfig = CollectConfiguration().everything()
    }

    override val info: SCMRepository
        get() = git.use { git ->
            runCatching {
                val head = git.repository.resolve(Constants.HEAD)
                val rw = RevWalk(git.repository)
                val root = rw.parseCommit(head)
                rw.sort(RevSort.REVERSE)
                rw.markStart(root)
                val lastCommit = rw.next()
                val origin = git.repository.config.getString("remote", "origin", "url")
                repoName = if (origin != null) GitRemoteRepository.repoNameFromURI(origin) else repoName
                return SCMRepository(this, origin, repoName, path, head.name, lastCommit.name)
            }.getOrElse { throw RuntimeException("Couldn't create JGit instance with path $path") }
        }

    @Deprecated("Use git")
    fun openRepository(): Git = Git.open(File(path)).also { git ->
        this.mainBranchName = this.mainBranchName ?: discoverMainBranchName(git)
    }

    val git: Git
        get() = runCatching {
            Git.open(File(path)).also { git ->
                this.mainBranchName = this.mainBranchName ?: discoverMainBranchName(git)
            }
        }.getOrElse { throw RuntimeException("Failed to open Git repository at $path", it) }

    private fun discoverMainBranchName(git: Git): String = git.repository.branch

    override val head: ChangeSet
        get() =
            git.use { git ->
                runCatching {
                    val head = git.repository.resolve(Constants.HEAD)
                    RevWalk(git.repository).use { revWalk ->
                        val r = revWalk.parseCommit(head)
                        ChangeSet(r.name, convertToDate(r))
                    }
                }.getOrElse { throw RuntimeException("Error in getHead() for $path", it) }
            }


    override fun createCommit(message: String) {
        git.use { git ->
            runCatching {
                val status = git.status().call()
                if (!status.hasUncommittedChanges()) return
                git.add().apply { status.modified.forEach { addFilepattern(it) }; call() }
                git.commit().setMessage(message).call()
            }.getOrElse { throw RuntimeException("Error in create commit for $path", it) }
        }
    }

    override fun resetLastCommitsWithMessage(message: String) {
        git.use { git ->
            runCatching {
                val commit: RevCommit? = git.log().call().firstOrNull { it.fullMessage.contains(message) }
                if (commit != null) {
                    git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(extractChangeSet(commit).id).call()
                } else log.info("Reset doesn't required")
            }.getOrElse { throw RuntimeException("Reset failed", it) }
        }
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
    override fun getCommit(id: String): Commit? = git.use { git ->
        runCatching {
            val repo = git.repository
            val jgitCommit = git.log().add(repo.resolve(id)).call().firstOrNull() ?: return null

            /* Extract metadata. */
            val msg = if (collectConfig?.isCollectingCommitMessages == true) jgitCommit.fullMessage.trim() else ""
            val branches = getBranches(git, jgitCommit.name)
            val isCommitInMainBranch = branches.contains(this.mainBranchName)

            /* Create one of our Commit's based on the jgitCommit metadata. */
            val commit = Commit(jgitCommit, msg, branches, isCommitInMainBranch)

            /* Convert each of the associated DiffEntry's to a Modification. */
            val diffsForTheCommit = diffsForTheCommit(repo, jgitCommit) ?: return null
            if (diffsForTheCommit.size > maxNumberFilesInACommit) {
                throw VCSException("Commit $id touches more than $maxNumberFilesInACommit files")
            }

            diffsForTheCommit
                .filter { diffFiltersAccept(it) }
                .map { diffToModification(repo, it) }
                .forEach { commit.modifications.add(it) }

            commit
        }.getOrElse { throw RuntimeException("Error detailing $id in $path", it) }
    }

    private fun getBranches(git: Git, hash: String): Set<String?> {
        if (!collectConfig!!.isCollectingBranches) return HashSet()

        val gitBranches = git.branchList().setContains(hash).call()
        return gitBranches.stream()
            .map { ref: Ref -> ref.name.substring(ref.name.lastIndexOf("/") + 1) }
            .collect(Collectors.toSet())
    }

    override val allBranches: List<Ref>
        get() = git.use { git ->
            runCatching { git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call() }
                .getOrElse { throw RuntimeException("Error getting branches in $path", it) }
        }


    override val allTags: List<Ref>
        get() = git.use {
            runCatching { it.tagList().call() }
                .getOrElse { throw RuntimeException("Error getting tags in $path", it) }
        }

    override fun checkoutTo(branch: String) {
        git.use { git ->
            if (git.repository.isBare) throw CheckoutException("Error repo is bare")
            if (git.repository.findRef(branch) == null) {
                throw CheckoutException("Branch does not exist: $branch")
            }

            if (git.status().call().hasUncommittedChanges()) {
                throw CheckoutException("There are uncommitted changes in the working directory")
            }
            runCatching { git.checkout().setName(branch).call() }
                .getOrElse { throw CheckoutException("Error checking out to $branch") }
        }
    }

    override val currentBranchOrTagName: String
        get() = git.use { git ->
            runCatching {
                val head = git.repository.resolve("HEAD")
                git.repository.allRefsByPeeledObjectId[head]!!
                    .map { it.name }.distinct().first { it: String -> it.startsWith("refs/") }
            }.getOrElse { throw RuntimeException("Error getting branch name", it) }
        }

    private fun diffToModification(repo: Repository, diff: DiffEntry): Modification {
        val change = enumValueOf<ModificationType>(diff.changeType.toString())

        val oldPath = diff.oldPath
        val newPath = diff.newPath

        val sourceCode = getSourceCode(repo, diff)
        val diffText = if (diff.changeType != DiffEntry.ChangeType.DELETE) {
            getDiffText(repo, diff).let { diffContent ->
                if (diffContent.length > maxSizeOfDiff) "-- TOO BIG --" else diffContent
            }
        } else ""

        return Modification(oldPath, newPath, change, diffText, sourceCode)
    }

    private fun diffsForTheCommit(repo: Repository, commit: RevCommit): List<DiffEntry>? {
        val currentCommit: AnyObjectId = repo.resolve(commit.name)
        val parentCommit: AnyObjectId? = if (commit.parentCount > 0) repo.resolve(commit.getParent(0).name) else null

        return this.getDiffBetweenCommits(repo, parentCommit, currentCommit)
    }

    override fun getDiffBetweenCommits(priorCommit: String, laterCommit: String): List<Modification> {
        return git.use { git ->
            runCatching {
                val repo = git.repository
                val prior: AnyObjectId = repo.resolve(priorCommit)
                val later: AnyObjectId = repo.resolve(laterCommit)

                this.getDiffBetweenCommits(repo, prior, later)
                    .map { diff: DiffEntry -> return@map this.diffToModification(repo, diff) }
            }.getOrElse { throw RuntimeException("Error diffing $priorCommit and $laterCommit in $path", it) }
        }
    }

    private fun getDiffBetweenCommits(
        repo: Repository, parentCommit: AnyObjectId?,
        currentCommit: AnyObjectId
    ): List<DiffEntry> = runCatching {
        DiffFormatter(DisabledOutputStream.INSTANCE).use { df ->
            df.apply {
                setBinaryFileThreshold(2 * 1024) // 2 mb max a file
                setRepository(repo)
                setDiffComparator(RawTextComparator.DEFAULT)
                isDetectRenames = true
                setContext(df)
            }

            if (parentCommit == null) {
                RevWalk(repo).use { rw ->
                    rw.parseCommit(currentCommit).let {
                        df.scan(EmptyTreeIterator(), CanonicalTreeParser(null, rw.objectReader, it.tree))
                    }
                }
            } else {
                df.scan(parentCommit, currentCommit)
            }
        }
    }.getOrElse {
        throw RuntimeException("Error diffing ${parentCommit!!.name} and ${currentCommit.name} in $path", it)
    }

    private fun setContext(df: DiffFormatter) {
        runCatching {
            val context = getSystemProperty("git.diffcontext") /* TODO: make it into a configuration */
            df.setContext(context)
        }.onFailure { it.printStackTrace() }
    }

    private fun getSourceCode(repo: Repository, diff: DiffEntry): String {
        if (!collectConfig!!.isCollectingSourceCode) return ""
        return runCatching {
            val reader = repo.newObjectReader()
            val bytes = reader.open(diff.newId.toObjectId()).bytes
            String(bytes, charset("utf-8"))
        }.getOrElse { throw RuntimeException("Failure in getSourceCode()", it) }
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
        git.use { git ->
            runCatching {
                git.reset().setMode(ResetCommand.ResetType.HARD).call()
                git.checkout().setName(mainBranchName).call()
                deleteMMBranch(git)
                git.checkout().setCreateBranch(true).setName(BRANCH_MM).setStartPoint(hash).setForced(true)
                    .setOrphan(true).call()
            }.onFailure { throw RuntimeException(it) }
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
        git.use { git ->
            runCatching {
                git.checkout().setName(mainBranchName).setForced(true).call()
                git.branchDelete().setBranchNames(BRANCH_MM).setForce(true).call()
            }.onFailure { it.printStackTrace() }
        }
    }

    private val allFilesInPath: List<File> get() = RDFileUtils.getAllFilesInPath(path)

    override val totalCommits: Long get() = changeSets.size.toLong()

    override val developerInfo get() = getDeveloperInfo(null)

    override fun dbPrepared() =
        GitRepositoryUtil.dbPrepared(git, vcsDataBase!!, projectId!!, filePathMap, developersMap)

    override fun getDeveloperInfo(nodePath: String?): Map<String, DeveloperInfo> {
        return GitRepositoryUtil.
        getDeveloperInfo(git, vcsDataBase!!, projectId!!, filePathMap, developersMap, path, nodePath, files())
    }

    override fun getCommitFromTag(tag: String): String =
        git.use { git ->
            runCatching {
                git.log().add(getActualRefObjectId(git.repository.findRef(tag), git.repository)).call().first().name
            }.getOrElse { throw RuntimeException("Failed for tag $tag", it) }
        }


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

    override fun clone(dest: Path): SCM {
        log.info("Cloning to $dest")
        RDFileUtils.copyDirTree(Paths.get(path), dest)
        return GitRepository(dest.toString())
    }

    override fun delete() {
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

    companion object {
        /* Constants. */
        private const val MAX_SIZE_OF_A_DIFF = 100000
        private const val DEFAULT_MAX_NUMBER_OF_FILES_IN_A_COMMIT = 5000
        private const val BRANCH_MM = "mm" /* TODO mm -> rd. */
        private val log: Logger = LoggerFactory.getLogger(GitRepository::class.java)
        private val developersMap = ConcurrentHashMap<String, DeveloperInfo>()
        private val filePathMap = ConcurrentHashMap<String, Long>()
    }
}
