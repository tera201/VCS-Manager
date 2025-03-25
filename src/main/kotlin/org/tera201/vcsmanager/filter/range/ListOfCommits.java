package org.tera201.vcsmanager.filter.range;

import org.tera201.vcsmanager.domain.ChangeSet;
import org.tera201.vcsmanager.scm.SCM;

import java.util.ArrayList;
import java.util.List;

public class ListOfCommits implements CommitRange {

	private List<String> commits;

	public ListOfCommits(List<String> commits) {
		this.commits = commits;
	}
	
	@Override
	public List<ChangeSet> get(SCM scm) {
		List<ChangeSet> all = scm.getChangeSets();

		List<ChangeSet> filtered = new ArrayList<ChangeSet>();
		for(ChangeSet cs : all) {
			if(commits.contains(cs.getId())) {
				filtered.add(cs);
			}
		}
		
		return filtered;
	}

}
