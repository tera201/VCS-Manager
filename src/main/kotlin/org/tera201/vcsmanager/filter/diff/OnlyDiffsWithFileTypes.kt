package org.tera201.vcsmanager.filter.diff;

import org.tera201.vcsmanager.util.RDFileUtils;

import java.util.List;

/**
 * Only process diffs on files with certain file extensions.
 * 
 */
public class OnlyDiffsWithFileTypes implements DiffFilter {

	private List<String> fileExtensions;
	
	public OnlyDiffsWithFileTypes(List<String> fileExtensions) {
		this.fileExtensions = fileExtensions;
	}
	
	@Override
	public boolean accept(String diffEntryPath) {
		return RDFileUtils.fileNameHasIsOfType(diffEntryPath, this.fileExtensions);
	}

}
