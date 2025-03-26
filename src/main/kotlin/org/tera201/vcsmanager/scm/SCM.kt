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
    /* Methods for general information about the SCM. */
    /**
     * @return Total commits in this SCM.
     */
    fun totalCommits(): Long

    /**
     * @return ChangeSet representing the "head" (most recent) commit.
     */
	val head: ChangeSet

    /**
     * @return All ChangeSets in this SCM.
     */
	val changeSets: List<ChangeSet>

    fun createCommit(message: String)

    fun resetLastCommitsWithMessage(message: String)

    /**
     * @return Metadata about this SCM.
     */
    fun info(): SCMRepository?

    /* Methods for retrieving Commits. */
    /**
     * Retrieve the Commit with this id.
     *
     * @param id        The commit to retrieve
     * @return        The Commit with this id, or null.
     */
    fun getCommit(id: String): Commit?

	val allBranches: List<Ref>

	val allTags: List<Ref>

    @Throws(CheckoutException::class)
    fun checkoutTo(branch: String)
    val currentBranchOrTagName: String

    /* TODO A method named getCommitXYZ should return a Commit. */
    fun getCommitFromTag(tag: String): String

    /**
     * Get the diff between the specified commits.
     *
     * @param priorCommit    The first (old) commit
     * @param laterCommit    The second (new) commit
     * @return A list of Modification objects representing the changes between
     * priorCommit and laterCommit.
     */
    fun getDiffBetweenCommits(priorCommit: String, laterCommit: String): List<Modification>
    fun repositoryAllSize(): Map<String, CommitSize>
    fun repositorySize(filePath: String): Map<String, CommitSize>
    fun repositorySize(branchOrTag: String, filePath: String): Map<String, CommitSize>

    @Throws(GitAPIException::class, IOException::class)
    fun dbPrepared()

    fun blame(file: String): List<BlamedLine>
    fun blameManager(): BlameManager?
    fun blame(file: String, commitToBeBlamed: String, priorCommit: Boolean): List<BlamedLine>

	@get:Throws(IOException::class, GitAPIException::class)
    val developerInfo: Map<String, DeveloperInfo>

    @Throws(IOException::class, GitAPIException::class)
    fun getDeveloperInfo(nodePath: String?): Map<String, DeveloperInfo>

    /* Methods for interacting with current repo state. */
    /**
     * Return the repo to the state immediately following the application of the Commit identified by this id.
     * @param id    The commit to checkout.
     * Implementors: May not be thread safe, consider synchronized.
     */
    fun checkout(id: String)

    /**
     * Return the repo to the state of the head commit.
     * Implementors: May not be thread safe, consider synchronized.
     */
    fun reset()

    /**
     * @return All files currently in the repo.
     * Implementors: May not be thread safe, consider synchronized.
     */
    fun files(): List<RepositoryFile>

    /**
     * Duplicate this SCM.
     *
     * @param dest On-disk records will be rooted here (e.g. "/tmp/clone-here")
     * @returns An SCM corresponding to the copy
     */
    fun clone(dest: Path): SCM

    /**
     * Delete any local storage devoted to this SCM.
     * Should be safe to call repeatedly without ill effect.
     */
    fun delete()

    /**
     * Configure which data should be extracted from the repository.
     * (usually for performance reasons)
     *
     * Default should be to collect *everything*
     */
    fun setDataToCollect(config: CollectConfiguration)
}
