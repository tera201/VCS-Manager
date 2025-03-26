package org.tera201.vcsmanager.filter.diff

/**
 * Filter out unwanted Diffs based on file extensions.
 *
 */
interface DiffFilter {
    /**
     * Determine whether to accept a diff on this file for further processing.
     *
     * @param diffEntryPath The path to the file being diffed
     * @return True if the file should pass the filter, false if it should be filtered out.
     */
    fun accept(diffEntryPath: String): Boolean
}
