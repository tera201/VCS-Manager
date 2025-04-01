package org.tera201.vcsmanager.filter.rev

import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter

class FirstParentFilter : RevFilter() {
    private val ignoreCommits: MutableSet<RevCommit> = HashSet()

    override fun include(revWalk: RevWalk, commit: RevCommit): Boolean {
        if (commit.parentCount > 1) ignoreCommits.add(commit.getParent(1))

        return if (ignoreCommits.contains(commit)) {
            ignoreCommits.remove(commit)
            false
        } else {
            true
        }
    }

    override fun clone(): RevFilter = FirstParentFilter()
}