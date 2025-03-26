package org.tera201.vcsmanager.scm;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.tera201.vcsmanager.domain.ChangeSet;
import org.tera201.vcsmanager.domain.Commit;
import org.tera201.vcsmanager.domain.Modification;
import org.tera201.vcsmanager.scm.entities.BlameManager;
import org.tera201.vcsmanager.scm.entities.BlamedLine;
import org.tera201.vcsmanager.scm.entities.CommitSize;
import org.tera201.vcsmanager.scm.entities.DeveloperInfo;
import org.tera201.vcsmanager.scm.exceptions.CheckoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface SCM {

	/* Methods for general information about the SCM. */

	/**
	 * @return Total commits in this SCM.
	 */
	long totalCommits();

	/**
	 * @return ChangeSet representing the "head" (most recent) commit.
	 */
	ChangeSet getHead();

	/**
	 * @return All ChangeSets in this SCM.
	 */
	List<ChangeSet> getChangeSets();

	void createCommit(String message);

	void resetLastCommitsWithMessage(String message);

	/**
	 * @return Metadata about this SCM.
	 */
	SCMRepository info();

	/* Methods for retrieving Commits. */

	/**
	 * Retrieve the Commit with this id.
	 *
	 * @param id		The commit to retrieve
	 * @return  		The Commit with this id, or null.
	 */
	Commit getCommit(String id);

	List<Ref> getAllBranches();

	List<Ref> getAllTags();

	void checkoutTo(String branch) throws CheckoutException;
	String getCurrentBranchOrTagName();
	
	/* TODO A method named getCommitXYZ should return a Commit. */
	String getCommitFromTag(String tag);

	/**
	 * Get the diff between the specified commits.
	 *
	 * @param priorCommit	The first (old) commit
	 * @param laterCommit	The second (new) commit
	 * @return A list of Modification objects representing the changes between
	 * 			priorCommit and laterCommit.
	 */
	List<Modification> getDiffBetweenCommits(String priorCommit, String laterCommit);
	Map<String, CommitSize> repositoryAllSize();
	Map<String, CommitSize> currentRepositorySize();
	Map<String, CommitSize> repositorySize(String filePath);
	Map<String, CommitSize> repositorySize(String branchOrTag, String filePath);
	void dbPrepared() throws GitAPIException, IOException;

	@Deprecated
	String blame(String file, String currentCommit, Integer line);
	List<BlamedLine> blame(String file);
	BlameManager blameManager();
	List<BlamedLine> blame(String file, String commitToBeBlamed, boolean priorCommit);
	Map<String, DeveloperInfo> getDeveloperInfo() throws IOException, GitAPIException;
	Map<String, DeveloperInfo> getDeveloperInfo(String nodePath) throws IOException, GitAPIException;

	/* Methods for interacting with current repo state. */

	/**
	 * Return the repo to the state immediately following the application of the Commit identified by this id.
	 * @param id	The commit to checkout.
	 * Implementors: May not be thread safe, consider synchronized.
	 */
	void checkout(String id);

	/**
	 * Return the repo to the state of the head commit.
	 * Implementors: May not be thread safe, consider synchronized.
	 */
	void reset();

	/**
	 * @return All files currently in the repo.
	 * Implementors: May not be thread safe, consider synchronized.
	 */
	List<RepositoryFile> files();

	/**
	 * Duplicate this SCM.
	 *
	 * @param dest On-disk records will be rooted here (e.g. "/tmp/clone-here")
	 * @returns An SCM corresponding to the copy
	 */
	SCM clone(Path dest);

	/**
	 * Delete any local storage devoted to this SCM.
	 * Should be safe to call repeatedly without ill effect.
	 */
	void delete();

	/**
	 * Configure which data should be extracted from the repository.
	 * (usually for performance reasons)
	 *
	 * Default should be to collect *everything*
	 */
	void setDataToCollect(CollectConfiguration config);
}
