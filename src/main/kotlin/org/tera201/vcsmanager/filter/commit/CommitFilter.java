package org.tera201.vcsmanager.filter.commit;

import org.tera201.vcsmanager.domain.Commit;

public interface CommitFilter {

	/**
	 * Determine whether to accept this commit for further processing.
	 *
	 * @param commit	Commit in question
	 * @return	True if commit should pass the filter, false if it should be filtered out.
	 */
	boolean accept(Commit commit);

}
