package org.tera201.vcsmanager.filter.range;

import org.tera201.vcsmanager.domain.ChangeSet;
import org.tera201.vcsmanager.scm.SCM;

import java.util.LinkedList;
import java.util.List;

public class DailyCommits implements CommitRange {

	private final long daysInMillis;

	public DailyCommits(int days) {
		daysInMillis = 1000L * 60L * 60L * 24L * days;
	}

	@Override
	public List<ChangeSet> get(SCM scm) {

		List<ChangeSet> all = scm.getChangeSets();

		LinkedList<ChangeSet> filtered = new LinkedList<ChangeSet>();
		filtered.add(all.get(0));

		for(ChangeSet cs : all) {
			if(isFarFromTheLastOne(cs, filtered)) {
				filtered.addLast(cs);
			}
		}

		return filtered;
	}

	private boolean isFarFromTheLastOne(ChangeSet cs, LinkedList<ChangeSet> filtered) {
		ChangeSet lastOne = filtered.getLast();

		long lastInMillis = lastOne.getTime().getTimeInMillis();
		long currentInMillis = cs.getTime().getTimeInMillis();

		return (lastInMillis - currentInMillis >= daysInMillis);
	}

}
