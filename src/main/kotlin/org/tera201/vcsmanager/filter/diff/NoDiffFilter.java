package org.tera201.vcsmanager.filter.diff;

/**
 * Default filter that accepts diffs on all files.
 * 
 */
public class NoDiffFilter implements DiffFilter {

	@Override
	public boolean accept(String diffEntryPath) {
		return true;
	}
}