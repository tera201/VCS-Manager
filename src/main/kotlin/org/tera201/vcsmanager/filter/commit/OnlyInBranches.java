package org.tera201.vcsmanager.filter.commit;

import org.tera201.vcsmanager.domain.Commit;

import java.util.List;

public class OnlyInBranches implements CommitFilter{

	private List<String> branches;

	public OnlyInBranches(List<String> branches) {
		this.branches = branches;
	}
	
	@Override
	public boolean accept(Commit commit) {
		return commit.getBranches().stream().anyMatch(commitBranch -> branches.stream().anyMatch(branch -> branch.equals(commitBranch)));
	}

}
