package org.tera201.vcsmanager.scm

import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.tera201.vcsmanager.domain.*
import org.tera201.vcsmanager.scm.entities.*
import org.tera201.vcsmanager.scm.services.GitOperations
import org.tera201.vcsmanager.scm.services.GitRepositoryUtil
import org.tera201.vcsmanager.util.VCSDataBase
import org.tera201.vcsmanager.util.RDFileUtils
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/* TODO Name: Sounds like it inherits SCMRepository, but it actually implements SCM. */
open class GitRepository
    (var path: String = "", var repoName: String, protected var firstParentOnly: Boolean = false, vcsDataBase: VCSDataBase? = null) : SCM {
    private var vcsDataBase: VCSDataBase
    //TODO should be singleton
    override var collectConfig: CollectConfiguration = CollectConfiguration().everything()
    private val projectId: Int
        get() = vcsDataBase.getProjectId(repoName, path) ?: this.vcsDataBase.insertProject(repoName, path)
    override val changeSets: List<ChangeSet>
        get() = if (!firstParentOnly) gitOps.getAllCommits() else gitOps.firstParentsCommitOnly()
    private val gitOps: GitOperations = GitOperations(path)
    private val allFilesInPath: List<File> get() = RDFileUtils.getAllFilesInPath(path)
    override val totalCommits: Long get() = changeSets.size.toLong()
    override val developerInfo get() = getDeveloperInfo(null)
    override val head: ChangeSet get() = gitOps.getHeadCommit()

    init {
        log.debug("Creating a GitRepository from path $path")
        this.vcsDataBase = vcsDataBase ?:  VCSDataBase("$path/repository.db")
        val splitPath = path.replace("\\", "/").split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        repoName = if (splitPath.isNotEmpty()) splitPath.last() else ""
    }

    override val info: SCMRepository
        get() = runCatching {
            SCMRepository(this, gitOps.getOrigin(), repoName, path, gitOps.getHeadCommit().id, gitOps.getLastCommit().id)
        }.getOrElse { throw RuntimeException("Couldn't create JGit instance with path $path") }

    override fun createCommit(message: String) = gitOps.createCommit(message)

    override fun resetLastCommitsWithMessage(message: String) = gitOps.resetLastCommitsWithMessage(message)

    override fun getCommit(id: String): Commit? = gitOps.getCommit(id)

    override val allBranches: List<Ref> get() = gitOps.getAllBranches()

    override val allTags: List<Ref> get() = gitOps.getAllTags()

    override val currentBranchOrTagName: String get() = gitOps.getCurrentBranchOrTagName()

    override fun checkoutTo(branch: String) = gitOps.checkoutBranch(branch)

    override fun getDiffBetweenCommits(priorCommit: String, laterCommit: String): List<Modification> =
        getDiffBetweenCommits(priorCommit, laterCommit)

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
