package org.tera201.vcsmanager.scm

import kotlinx.coroutines.runBlocking
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tera201.vcsmanager.domain.*
import org.tera201.vcsmanager.db.entities.*
import org.tera201.vcsmanager.scm.services.GitOperations
import org.tera201.vcsmanager.scm.services.GitRepositoryUtil
import org.tera201.vcsmanager.db.VCSDataBase
import org.tera201.vcsmanager.scm.services.DiffService
import org.tera201.vcsmanager.util.RDFileUtils
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/* TODO Name: Sounds like it inherits SCMRepository, but it actually implements SCM. */
open class GitRepository
    (path: String = "", val repoName: String, protected var firstParentOnly: Boolean = false, vcsDataBase: VCSDataBase? = null) : SCM {
    private var vcsDataBase: VCSDataBase
    //TODO should be singleton
    override var collectConfig: CollectConfiguration = CollectConfiguration().everything()
    var path: String = path
        set(value) {
            field = value
            gitOps.path = value
        }
    private val projectId: Int
        get() = vcsDataBase.getProjectId(repoName, path) ?: this.vcsDataBase.insertProject(repoName, path)
    override val changeSets: List<ChangeSet>
        get() = if (!firstParentOnly) gitOps.getAllCommits() else gitOps.firstParentsCommitOnly()
    private var gitOps: GitOperations = GitOperations(path)
    private var diffService: DiffService = DiffService(gitOps)
    private var gitRepositoryUtil: GitRepositoryUtil
    private val allFilesInPath: List<File> get() = RDFileUtils.getAllFilesInPath(path)
    override val totalCommits: Long get() = changeSets.size.toLong()
    override val developerInfo get() = getDeveloperInfo(null)
    override val head: ChangeSet get() = gitOps.getHeadCommit()

    init {
        log.debug("Creating a GitRepository from path $path")
        this.vcsDataBase = vcsDataBase ?:  VCSDataBase("$path/repository.db")
        gitRepositoryUtil = GitRepositoryUtil(gitOps, vcsDataBase!!, projectId)
        val splitPath = path.replace("\\", "/").split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
//        repoName = if (splitPath.isNotEmpty()) splitPath.last() else ""
    }

    override val info: SCMRepository
        get() = runCatching {
            SCMRepository(this, gitOps.getOrigin(), repoName, path, gitOps.getHeadCommit().id, gitOps.getLastCommit().id)
        }.getOrElse { throw RuntimeException("Couldn't create JGit instance with path $path") }

    override fun getRepositorySize(all: Boolean, branchOrTag: String?, filePath: String?): Map<String, CommitSize> =
        gitRepositoryUtil.repositorySize(path, filePath)

    override fun createCommit(message: String) = gitOps.createCommit(message)

    override fun resetLastCommitsWithMessage(message: String) = gitOps.resetLastCommitsWithMessage(message)

    override fun getCommit(id: String): Commit? = gitOps.getCommit(id)

    override val allBranches: List<Ref> get() = gitOps.getAllBranches()

    override val allTags: List<Ref> get() = gitOps.getAllTags()

    override val currentBranchOrTagName: String get() = gitOps.getCurrentBranchOrTagName()

    override fun checkoutTo(branch: String) = gitOps.checkoutBranch(branch)

    override fun getDiffBetweenCommits(priorCommit: String, laterCommit: String): List<Modification> =
        diffService.getDiffBetweenCommits(priorCommit, laterCommit)

    @Synchronized
    override fun checkout(hash: String) = gitOps.checkoutHash(hash)

    @Synchronized
    override fun files(): List<RepositoryFile> = allFilesInPath.map { RepositoryFile(it) }

    @Synchronized
    override fun reset() = gitOps.reset()

    override fun dbPrepared() =
        runBlocking { gitRepositoryUtil.dbPrepared(filePathMap) }

    override fun getDeveloperInfo(nodePath: String?): Map<String, DeveloperInfo> =
        runBlocking { gitRepositoryUtil.getDeveloperInfo(filePathMap, developersMap, path, nodePath, files()) }

    override fun getCommitFromTag(tag: String): String = gitOps.getCommitByTag(tag)

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

    companion object {
        /* Constants. */
        private val log: Logger = LoggerFactory.getLogger(GitRepository::class.java)
        private val developersMap = ConcurrentHashMap<String, DeveloperInfo>()
        private val filePathMap = ConcurrentHashMap<String, Long>()
    }
}
