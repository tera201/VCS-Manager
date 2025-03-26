package org.tera201.vcsmanager.filter.diff;

import org.tera201.vcsmanager.util.RDFileUtils;

import java.util.List;

/**
 * Only process diffs on files without certain file extensions.
 * 
 *
 */
public class OnlyDiffsWithoutFileTypes implements DiffFilter {
	
	private List<String> fileExtensions;
	
	public OnlyDiffsWithoutFileTypes(List<String> fileExtensions) {
		this.fileExtensions = fileExtensions;
	}

	@Override
	public boolean accept(String diffEntryPath) {
		return !RDFileUtils.fileNameHasIsOfType(diffEntryPath, this.fileExtensions);
	}
}
