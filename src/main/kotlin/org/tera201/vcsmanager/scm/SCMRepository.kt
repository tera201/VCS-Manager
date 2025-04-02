package org.tera201.vcsmanager.scm

data class SCMRepository(
    val scm: SCM, // e.g. GitHub URL
    val origin: String,
    val repoName: String,
    val path: String, // Local file system path
    val headCommit: String, // Most recent commit
    val firstCommit: String // First commit
) {

    /** Returns the origin URL or the local path if origin is not provided. */
    fun getOrigin(): String = origin ?: path

    /** Returns the last directory in the path. */
    val lastDir: String
        get() {
            val dirs = path.replace("\\", "/").split("/".toRegex()).filter { it.isNotEmpty() }
            return dirs.lastOrNull() ?: ""
        }

    /** Provides a string representation of the SCM repository. */
    override fun toString(): String {
        return "SCMRepository(path='$path', headCommit='$headCommit', firstCommit='$firstCommit', scm=$scm, origin=$origin)"
    }
}