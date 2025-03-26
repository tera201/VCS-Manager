package org.tera201.vcsmanager.filter.diff

import org.tera201.vcsmanager.util.RDFileUtils.fileNameHasIsOfType

/**
 * Only process diffs on files with certain file extensions.
 *
 */
class OnlyDiffsWithFileTypes(private val fileExtensions: List<String>) : DiffFilter {
    override fun accept(diffEntryPath: String): Boolean = fileNameHasIsOfType(diffEntryPath, this.fileExtensions)
}
