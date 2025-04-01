package org.tera201.vcsmanager.scm.services


object PropertyService {
    private const val MAX_SIZE_OF_A_DIFF = 100000
    private const val DEFAULT_MAX_NUMBER_OF_FILES_IN_A_COMMIT = 5000

    /** Get this system property (environment variable)'s value as an integer. */
    fun getSystemProperty(name: String): Int {
        val `val` = System.getProperty(name)
        return `val`.toInt()
    }

    /**
     * Returns the max diff size in bytes, defaulting to a large value.
     * Can be overridden with the "git.maxdiff" environment variable.
     */
    fun getMaxSizeOfDiff(): Int =
        runCatching { getSystemProperty("git.maxdiff") }.getOrDefault(MAX_SIZE_OF_A_DIFF)

    /**
     * Returns the max number of files in a commit, defaulting to a large value.
     * Can be overridden with the "git.maxfiles" environment variable.
     */
    fun getMaxNumberOfFiles(): Int =
        runCatching { getSystemProperty("git.maxfiles") }.getOrDefault(DEFAULT_MAX_NUMBER_OF_FILES_IN_A_COMMIT)
}