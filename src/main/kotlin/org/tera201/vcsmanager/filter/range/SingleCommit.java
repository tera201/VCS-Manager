package org.tera201.vcsmanager.filter.range;

import org.tera201.vcsmanager.domain.ChangeSet;
import org.tera201.vcsmanager.scm.SCM;

import java.util.Arrays;
import java.util.List;

public class SingleCommit implements CommitRange {

	private String commit;

	public SingleCommit(String commit) {
		this.commit = commit;
	}

	@Override
	public List<ChangeSet> get(SCM scm) {
		List<ChangeSet> commits = scm.getChangeSets();
		
		for(ChangeSet cs : commits) {
			if(cs.getId().equals(commit)) 
				return Arrays.asList(cs);
		}
		throw new RuntimeException("commit " + commit + " does not exist");
		
	}

}
