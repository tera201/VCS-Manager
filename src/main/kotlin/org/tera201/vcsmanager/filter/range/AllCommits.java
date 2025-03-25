package org.tera201.vcsmanager.filter.range;

import org.tera201.vcsmanager.domain.ChangeSet;
import org.tera201.vcsmanager.scm.SCM;

import java.util.List;

public class AllCommits implements CommitRange {

	@Override
	public List<ChangeSet> get(SCM scm) {
		return scm.getChangeSets();
	}

}
