package org.tera201.vcsmanager.scm

import org.apache.commons.io.FileUtils
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
import org.tera201.vcsmanager.util.VCSDataBase
import org.tera201.vcsmanager.util.RDFileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/* TODO Name: Sounds like it inherits SCMRepository, but it actually implements SCM. */
open class GitRepository
    (var path: String = "", var repoName: String, protected var firstParentOnly: Boolean = false, vcsDataBase: VCSDataBase? = null) : SCM {
    private var vcsDataBase: VCSDataBase
    private var maxNumberFilesInACommit: Int = -1 /* TODO Expose an API to control this value? Also in SubversionRepository. */
    private var maxSizeOfDiff = -1 /* TODO Expose an API to control this value? Also in SubversionRepository. */
    private var collectConfig: CollectConfiguration = CollectConfiguration().everything()
    private val projectId: Int
        get() = vcsDataBase.getProjectId(repoName, path) ?: this.vcsDataBase.insertProject(repoName, path)
    override val changeSets: List<ChangeSet>
        get() = if (!firstParentOnly) gitOps.getAllCommits() else gitOps.firstParentsCommitOnly()
    private val gitOps: GitOperations = GitOperations(path)

    //TODO inject using git into  getMainBranchName method
    private var mainBranchName: String? = gitOps.getMainBranchName(gitOps.git)

    private val allFilesInPath: List<File> get() = RDFileUtils.getAllFilesInPath(path)

    override val totalCommits: Long get() = changeSets.size.toLong()

    override val developerInfo get() = getDeveloperInfo(null)

    override val head: ChangeSet get() = gitOps.getHeadCommit()

    init {
        log.debug("Creating a GitRepository from path $path")
        this.vcsDataBase = vcsDataBase ?:  VCSDataBase("$path/repository.db")
        val splitPath = path.replace("\\", "/").split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        repoName = if (splitPath.isNotEmpty()) splitPath.last() else ""
        maxNumberFilesInACommit = checkMaxNumberOfFiles()
        maxSizeOfDiff = checkMaxSizeOfDiff()
    }

    override val info: SCMRepository
        get() = gitOps.git.use { git ->
            runCatching {
                val head = git.repository.resolve(Constants.HEAD)
                val rw = RevWalk(git.repository)
                val root = rw.parseCommit(head)
                rw.sort(RevSort.REVERSE)
                rw.markStart(root)
                val lastCommit = rw.next()
                val origin = git.repository.config.getString("remote", "origin", "url")
                repoName = if (origin != null) GitRemoteRepository.repoNameFromURI(origin) else repoName
                SCMRepository(this, origin, repoName, path, head.name, lastCommit.name)
            }.getOrElse { throw RuntimeException("Couldn't create JGit instance with path $path") }
        }


    override fun createCommit(message: String) = gitOps.createCommit(message)

    override fun resetLastCommitsWithMessage(message: String) = gitOps.resetLastCommitsWithMessage(message)

    /** Get the commit with this commit id. */
    override fun getCommit(id: String): Commit? = gitOps.git.use { git ->
        runCatching {
            val repo = git.repository
            val jgitCommit = git.log().add(repo.resolve(id)).call().firstOrNull() ?: return null

            /* Extract metadata. */
            val msg = if (collectConfig.isCollectingCommitMessages) jgitCommit.fullMessage.trim() else ""
            val branches = gitOps.getBranchesForHash(git, jgitCommit.name)
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

    override val allBranches: List<Ref> get() = gitOps.getAllBranches()

    override val allTags: List<Ref> get() = gitOps.getAllTags()

    override val currentBranchOrTagName: String get() = gitOps.getCurrentBranchOrTagName()

    override fun checkoutTo(branch: String) = gitOps.checkoutBranch(branch)

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

    private fun diffsForTheCommit(repo: Repository, commit: RevCommit): List<DiffEntry> {
        val currentCommit: AnyObjectId = repo.resolve(commit.name)
        val parentCommit: AnyObjectId? = if (commit.parentCount > 0) repo.resolve(commit.getParent(0).name) else null

        return this.getDiffBetweenCommits(repo, parentCommit, currentCommit)
    }

    override fun getDiffBetweenCommits(priorCommit: String, laterCommit: String): List<Modification> {
        return gitOps.git.use { git ->
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
        if (!collectConfig.isCollectingSourceCode) return ""
        return runCatching {
            val reader = repo.newObjectReader()
            val bytes = reader.open(diff.newId.toObjectId()).bytes
            String(bytes, charset("utf-8"))
        }.getOrElse { throw RuntimeException("Failure in getSourceCode()", it) }
    }

    private fun getDiffText(repo: Repository, diff: DiffEntry): String {
        if (!collectConfig.isCollectingDiffs) return ""

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
    override fun checkout(hash: String) = gitOps.checkoutHash(hash)

    @Synchronized
    override fun files(): List<RepositoryFile> = allFilesInPath.map { RepositoryFile(it) }

    @Synchronized
    override fun reset() = gitOps.reset()

    override fun dbPrepared() =
        GitRepositoryUtil.dbPrepared(gitOps.git, vcsDataBase, projectId, filePathMap, developersMap)

    override fun getDeveloperInfo(nodePath: String?): Map<String, DeveloperInfo> {
        return GitRepositoryUtil.
        getDeveloperInfo(gitOps.git, vcsDataBase, projectId, filePathMap, developersMap, path, nodePath, files())
    }

    override fun getCommitFromTag(tag: String): String = gitOps.getCommitByTag(tag)

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
        return GitRepository(dest.toString(), repoName= repoName)
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
        collectConfig.diffFilters.any { !it.accept(diff.newPath) }.not()

    companion object {
        /* Constants. */
        private const val MAX_SIZE_OF_A_DIFF = 100000
        private const val DEFAULT_MAX_NUMBER_OF_FILES_IN_A_COMMIT = 5000
        private val log: Logger = LoggerFactory.getLogger(GitRepository::class.java)
        private val developersMap = ConcurrentHashMap<String, DeveloperInfo>()
        private val filePathMap = ConcurrentHashMap<String, Long>()
    }
}
