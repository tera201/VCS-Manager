package org.tera201.vcsmanager.scm

import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Ref
import org.tera201.vcsmanager.domain.ChangeSet
import org.tera201.vcsmanager.domain.Commit
import org.tera201.vcsmanager.domain.Modification
import org.tera201.vcsmanager.scm.entities.BlameManager
import org.tera201.vcsmanager.scm.entities.BlamedLine
import org.tera201.vcsmanager.scm.entities.CommitSize
import org.tera201.vcsmanager.scm.entities.DeveloperInfo
import org.tera201.vcsmanager.scm.exceptions.CheckoutException
import java.io.IOException
import java.nio.file.Path

interface SCM {

    // ===================== General Repository Information =====================

    /** Total number of commits in this SCM. */
    val totalCommits: Long

    /** The most recent commit (HEAD). */
    val head: ChangeSet

    /** List of all commits (ChangeSets) in this SCM. */
    val changeSets: List<ChangeSet>

    /** SCM metadata */
    fun info(): SCMRepository?

    // ===================== Commit Operations =====================

    /** Creates a new commit with the given [message]. */
    fun createCommit(message: String)

    /** Resets the last commit(s) that match the given [message]. */
    fun resetLastCommitsWithMessage(message: String)

    /** Retrieves a commit by its [id], or `null` if not found. */
    fun getCommit(id: String): Commit?

    /* TODO A method named getCommitXYZ should return a Commit. */
    /** Retrieves a commit associated with a specific [tag]. */
    fun getCommitFromTag(tag: String): String

    /** Retrieves all branches in the repository. */
    val allBranches: List<Ref>

    /** Retrieves all tags in the repository. */
    val allTags: List<Ref>

    /** Retrieves the current branch or tag name. */
    val currentBranchOrTagName: String

    @Throws(CheckoutException::class)
    fun checkoutTo(branch: String)

    /** Returns the repository to the state of a specific commit by [id]. */
    fun checkout(id: String)

    /** Resets the repository to the HEAD state. */
    fun reset()

    // ===================== Repository Size Information =====================

    /** Retrieves repository size metrics. */
    fun repositoryAllSize(): Map<String, CommitSize>

    /** Retrieves size information for a specific [filePath]. */
    fun repositorySize(filePath: String): Map<String, CommitSize>

    /** Retrieves size information for a given [branchOrTag] and [filePath]. */
    fun repositorySize(branchOrTag: String, filePath: String): Map<String, CommitSize>

    // ===================== Commit Differences =====================

    /** Retrieves a list of modifications between [priorCommit] and [laterCommit]. */
    fun getDiffBetweenCommits(priorCommit: String, laterCommit: String): List<Modification>

    // ===================== Blame Information =====================

    /**
     * Retrieves blame information for a given [file].
     */
    fun blame(file: String): List<BlamedLine>

    /** Retrieves a blame manager instance. */
    fun blameManager(): BlameManager?

    /**
     * Retrieves blame information for a [file], considering [commitToBeBlamed].
     * If [priorCommit] is `true`, retrieves the prior commit’s blame.
     */
    fun blame(file: String, commitToBeBlamed: String, priorCommit: Boolean): List<BlamedLine>

    // ===================== Developer Information =====================

    @get:Throws(IOException::class, GitAPIException::class)
    val developerInfo: Map<String, DeveloperInfo>

    @Throws(IOException::class, GitAPIException::class)
    fun getDeveloperInfo(nodePath: String?): Map<String, DeveloperInfo>

    // ===================== Repository State =====================

    /** Retrieves all files in the repository. */
    fun files(): List<RepositoryFile>

    /**
     * Clones the repository to the specified [dest] path.
     * @return A new `SCM` instance representing the cloned repository.
     */
    fun clone(dest: Path): SCM

    /**
     * Deletes any local storage associated with this SCM.
     * Safe to call multiple times without negative effects.
     */
    fun delete()

    // ===================== Configuration & Setup =====================

    /**
     * Configures which data should be extracted from the repository.
     * Default behavior should be to collect *everything*.
     */
    fun setDataToCollect(config: CollectConfiguration)

    /** Prepares the database, handling potential [GitAPIException] and [IOException]. */
    @Throws(GitAPIException::class, IOException::class)
    fun dbPrepared()
}
