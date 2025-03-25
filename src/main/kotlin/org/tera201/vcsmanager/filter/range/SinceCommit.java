package org.tera201.vcsmanager.filter.range;

import org.tera201.vcsmanager.domain.ChangeSet;
import org.tera201.vcsmanager.scm.SCM;

import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

public class SinceCommit implements CommitRange {
	
	private Calendar since;

	public SinceCommit(Calendar since) {
		this.since = since;
	}

	@Override
	public List<ChangeSet> get(SCM scm) {
		
		List<ChangeSet> all = scm.getChangeSets();
		
		LinkedList<ChangeSet> filtered = new LinkedList<ChangeSet>();
		
		for(ChangeSet cs : all) {
			if(isInTheRange(cs)) {
				filtered.addLast(cs);
			}
		}
		
		return filtered;
	}

	private boolean isInTheRange(ChangeSet cs) {
		return since.before(cs.getTime());
	}


}
