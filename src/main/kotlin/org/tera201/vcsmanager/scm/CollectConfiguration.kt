package org.tera201.vcsmanager.scm

import org.tera201.vcsmanager.filter.diff.DiffFilter
import org.tera201.vcsmanager.filter.diff.NoDiffFilter

class CollectConfiguration {
    var isCollectingSourceCode: Boolean = false
        private set
    var isCollectingDiffs: Boolean = false
        private set
    var isCollectingBranches: Boolean = false
        private set
    var isCollectingCommitMessages: Boolean = false
        private set
    var diffFilters: List<DiffFilter> = listOf<DiffFilter>(NoDiffFilter())
        private set

    /**
     * Configures repodriller to retrieve the current source code of each modified file.
     * @return the current collect configuration
     */
    fun sourceCode(): CollectConfiguration = apply { isCollectingSourceCode = true }

    /**
     * Configures repodriller to retrieve the diffs of all modified files in a commit.
     * @return the current collect configuration
     */
    fun diffs(): CollectConfiguration = apply { isCollectingDiffs = true }

    /**
     * Configures repodriller to retrieve diffs from a commit based on the specified filters
     * @return the current collect configuration
     */
    fun diffs(vararg diffFilters: DiffFilter): CollectConfiguration = apply {
        isCollectingDiffs = true
        this.diffFilters = diffFilters.toList()
    }

    /**
     * Configures repodriller to retrieve the branches that a commit belongs to.
     * Without this configuration, Commit#isMainBranch does not work.
     * @return the current collect configuration
     */
    fun branches(): CollectConfiguration = apply { isCollectingBranches = true }

    /**
     * Configures repodriller to extract the entire commit message of a commit
     * @return the current collect configuration
     */
    fun commitMessages(): CollectConfiguration = apply { isCollectingCommitMessages = true }


    /**
     * Configures repodriller to extract just the basic information of a repository,
     * such as authors, files changed, and commit dates.
     * @return the current collect configuration
     */
    fun basicOnly(): CollectConfiguration = apply {
        isCollectingSourceCode = false
        isCollectingDiffs = false
        isCollectingBranches = false
        isCollectingCommitMessages = false
    }

    /**
     * Configures repodriller to extract everything it can,
     * meaning, source code, diff, branches, and commit messages
     * @return the current collect configuration
     */
    fun everything(): CollectConfiguration = apply {
        isCollectingSourceCode = true
        isCollectingDiffs = true
        isCollectingBranches = true
        isCollectingCommitMessages = true
    }
}
