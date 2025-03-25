package org.tera201.vcsmanager.filter.range;

import org.tera201.vcsmanager.domain.ChangeSet;
import org.tera201.vcsmanager.scm.SCM;

import java.util.List;

/* TODO It's confusing that this interface is called CommitRange but it returns a list of ChangeSet's, not Commits. */
public interface CommitRange {

	/**
	 * Extract the desired commits from this SCM.
	 *
	 * @param scm	The SCM to probe.
	 * @return	List of the ChangeSet's in the range.
	 */
	List<ChangeSet> get(SCM scm);

}
