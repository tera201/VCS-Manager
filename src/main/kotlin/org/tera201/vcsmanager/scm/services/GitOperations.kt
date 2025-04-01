package org.tera201.vcsmanager.scm.services

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.tera201.vcsmanager.scm.exceptions.VCSException
import org.tera201.vcsmanager.domain.ChangeSet
import org.tera201.vcsmanager.domain.Commit
import org.tera201.vcsmanager.scm.CollectConfiguration
import org.tera201.vcsmanager.filter.rev.FirstParentFilter
import org.tera201.vcsmanager.scm.exceptions.CheckoutException
import java.io.File
import java.util.*
import java.util.stream.Collectors

class GitOperations(var path:String) {
    //TODO should be singleton
    private var collectConfig: CollectConfiguration = CollectConfiguration().everything()
    private val diffService: DiffService = DiffService(this)
    val git: Git get() = runCatching { Git.open(File(path)) }
            .getOrElse { throw VCSException("Failed to open Git repository at $path") }

    fun getHeadCommit(): ChangeSet = git.use { git ->
        runCatching {
            val head = git.repository.resolve(Constants.HEAD)
            val revCommit = RevWalk(git.repository).parseCommit(head)
            ChangeSet(revCommit.name, convertToDate(revCommit))
        }.getOrElse { throw RuntimeException("Error in getHead() for $path", it) }
    }

    fun getLastCommit(): ChangeSet = git.use { git ->
        runCatching {
            val lastCommit = git.log().setMaxCount(1).call().first()
            val revCommit = RevWalk(git.repository).parseCommit(lastCommit)
            ChangeSet(revCommit.name, convertToDate(revCommit))
        }.getOrElse { throw RuntimeException("Error in getHead() for $path", it) }
    }

    fun getOrigin(): String = runCatching {
        git.repository.config.getString("remote", "origin", "url")
    }.getOrElse { throw RuntimeException("Couldn't get origin for repo: $path") }

    private fun convertToDate(revCommit: RevCommit): GregorianCalendar = GregorianCalendar().apply {
        timeZone = TimeZone.getTimeZone(revCommit.authorIdent.zoneId)
        time = Date.from(revCommit.authorIdent.whenAsInstant)
    }

    fun getAllBranches(): List<Ref> = git.use { git ->
            runCatching { git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call() }
                .getOrElse { throw RuntimeException("Error getting branches in $path", it) }
        }

    fun getAllTags(): List<Ref>  = git.use {
            runCatching { it.tagList().call() }
                .getOrElse { throw RuntimeException("Error getting tags in $path", it) }
        }

    fun getAllCommits(): List<ChangeSet> = git.use { git ->
        runCatching { git.log().call().map { ChangeSet(it.name, convertToDate(it)) }.toList() }
            .getOrElse { throw VCSException("Error in getChangeSets for $path") }
    }

    fun firstParentsCommitOnly(): List<ChangeSet> {
        val allCs: MutableList<ChangeSet> = mutableListOf()
        git.use { git ->
            runCatching {
                git.repository.findRef(Constants.HEAD)?.let { headRef ->
                    RevWalk(git.repository).use { revWalk ->
                        revWalk.revFilter = FirstParentFilter()
                        revWalk.sort(RevSort.TOPO)

                        val headCommit = revWalk.parseCommit(headRef.objectId)
                        revWalk.markStart(headCommit)
                        revWalk.forEach { allCs.add(ChangeSet(it.name, convertToDate(it))) }
                    }
                }
            }.getOrElse { throw VCSException("HEAD reference not found") }
        }

        return allCs
    }

    fun getCurrentBranchOrTagName(): String = git.use { git ->
        runCatching {
            val head = git.repository.resolve("HEAD")
            git.repository.allRefsByPeeledObjectId[head]!!
                .map { it.name }.distinct().first { it: String -> it.startsWith("refs/") }
        }.getOrElse { throw RuntimeException("Error getting branch name", it) }
    }

    fun getBranchesForHash(git: Git, hash: String): Set<String?> {
        if (!collectConfig.isCollectingBranches) return HashSet()

        val gitBranches = git.branchList().setContains(hash).call()
        return gitBranches.stream()
            .map { ref: Ref -> ref.name.substring(ref.name.lastIndexOf("/") + 1) }
            .collect(Collectors.toSet())
    }

    fun checkoutBranch(branch: String) {
        git.use { git ->
            if (git.repository.isBare) throw CheckoutException("Error repo is bare")
            if (git.repository.findRef(branch) == null) throw CheckoutException("Branch does not exist: $branch")
            if (git.status().call().hasUncommittedChanges()) {
                throw CheckoutException("There are uncommitted changes in the working directory")
            }
            runCatching { git.checkout().setName(branch).call() }
                .getOrElse { throw CheckoutException("Error checking out to $branch") }
        }
    }

    fun checkoutHash(hash: String) {
        git.use { git ->
            runCatching {
                git.reset().setMode(ResetCommand.ResetType.HARD).call()
                git.checkout().setCreateBranch(false).setStartPoint(hash).setForced(true)
            }.onFailure { throw CheckoutException("Error checking out to $hash") }
        }
    }

    fun createCommit(message: String) {
        git.use { git ->
            runCatching {
                val status = git.status().call()
                if (!status.hasUncommittedChanges()) return
                git.add().apply { status.modified.forEach { addFilepattern(it) }; call() }
                git.commit().setMessage(message).call()
            }.getOrElse { throw RuntimeException("Error in create commit for $path", it) }
        }
    }

    fun resetLastCommitsWithMessage(message: String) {
        git.use { git ->
            runCatching {
                val commit: RevCommit? = git.log().call().firstOrNull { it.fullMessage.contains(message) }
                if (commit != null) {
                    git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(ChangeSet(commit.name, convertToDate(commit)).id).call()
                }
            }.getOrElse { throw RuntimeException("Reset failed", it) }
        }
    }

    fun getMainBranchName(): String = git.use { git ->
        runCatching { git.repository.branch }.getOrElse { throw VCSException("Failure in getMainBranchName()") }
    }

    /** Get the commit with this commit id. */
    fun getCommit(id: String): Commit? = git.use { git ->
        runCatching {
            val repo = git.repository
            val jgitCommit = git.log().add(repo.resolve(id)).call().firstOrNull() ?: return null

            /* Extract metadata. */
            val msg = if (collectConfig.isCollectingCommitMessages) jgitCommit.fullMessage.trim() else ""
            val branches = getBranchesForHash(git, jgitCommit.name)
            val isCommitInMainBranch = branches.contains(this.getMainBranchName())

            /* Create one of our Commit's based on the jgitCommit metadata. */
            val commit = Commit(jgitCommit, msg, branches, isCommitInMainBranch)

            /* Convert each of the associated DiffEntry's to a Modification. */
            val diffsForTheCommit = diffService.diffsForTheCommit(repo, jgitCommit)
            val maxNumberFilesInACommit = PropertyService.getMaxNumberOfFiles()
            if (diffsForTheCommit.size > maxNumberFilesInACommit) {
                throw VCSException("Commit $id touches more than $maxNumberFilesInACommit files")
            }

            diffsForTheCommit
                .filter { diffService.diffFiltersAccept(it) }
                .map { diffService.diffToModification(repo, it) }
                .forEach { commit.modifications.add(it) }

            commit
        }.getOrElse { throw RuntimeException("Error detailing $id in $path", it) }
    }

    @Synchronized
    fun reset() {
        git.use { git ->
            runCatching { git.checkout().setName(getMainBranchName()).setForced(true).call() }.onFailure { it.printStackTrace() }
        }
    }

    fun getSourceCode(repo: Repository, diff: DiffEntry): String {
        if (!collectConfig.isCollectingSourceCode) return ""
        return runCatching {
            val reader = repo.newObjectReader()
            val bytes = reader.open(diff.newId.toObjectId()).bytes
            String(bytes, charset("utf-8"))
        }.getOrElse { throw RuntimeException("Failure in getSourceCode()", it) }
    }

    fun getCommitByTag(tag: String): String =
        git.use { git ->
            runCatching {
                git.log().add(getActualRefObjectId(git.repository.findRef(tag), git.repository)).call().first().name
            }.getOrElse { throw RuntimeException("Failed for tag $tag", it) }
        }


    private fun getActualRefObjectId(ref: Ref, repo: Repository): ObjectId {
        val peeledId = repo.refDatabase.peel(ref).peeledObjectId
        return peeledId ?: ref.objectId
    }

}