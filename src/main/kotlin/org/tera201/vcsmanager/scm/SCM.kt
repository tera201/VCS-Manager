package org.tera201.vcsmanager.scm

import org.eclipse.jgit.lib.Ref
import org.tera201.vcsmanager.domain.ChangeSet
import org.tera201.vcsmanager.domain.Commit
import org.tera201.vcsmanager.domain.Modification
import org.tera201.vcsmanager.db.entities.DeveloperInfo
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
    val info: SCMRepository?

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

    fun checkoutTo(branch: String)

    /** Returns the repository to the state of a specific commit by [id]. */
    fun checkout(id: String)

    /** Resets the repository to the HEAD state. */
    fun reset()

    // ===================== Commit Differences =====================

    /** Retrieves a list of modifications between [priorCommit] and [laterCommit]. */
    fun getDiffBetweenCommits(priorCommit: String, laterCommit: String): List<Modification>

    // ===================== Developer Information =====================

    val developerInfo: Map<String, DeveloperInfo>

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
    var collectConfig: CollectConfiguration

    /** Prepares the database. */
    fun dbPrepared()
}
