package org.tera201.vcsmanager.filter.range;

import org.tera201.vcsmanager.domain.ChangeSet;
import org.tera201.vcsmanager.scm.SCM;

import java.util.Arrays;
import java.util.List;

public class OnlyInHead implements CommitRange {

	@Override
	public List<ChangeSet> get(SCM scm) {
		return Arrays.asList(scm.getHead());
	}

}
