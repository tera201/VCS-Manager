package org.tera201.vcsmanager.filter.commit;

import org.tera201.vcsmanager.domain.Commit;

public class NoFilter implements CommitFilter{

	public boolean accept(Commit commit) {
		return true;
	}

}
