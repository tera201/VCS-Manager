package org.tera201.vcsmanager.filter.diff

/**
 * Default filter that accepts diffs on all files.
 *
 */
class NoDiffFilter : DiffFilter {
    override fun accept(diffEntryPath: String): Boolean = true
}