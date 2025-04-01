package org.tera201.vcsmanager.scm.services

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.tera201.vcsmanager.domain.Modification
import org.tera201.vcsmanager.domain.ModificationType
import org.tera201.vcsmanager.scm.CollectConfiguration
import org.tera201.vcsmanager.scm.services.PropertyService.getMaxSizeOfDiff
import org.tera201.vcsmanager.scm.services.PropertyService.getSystemProperty
import java.io.ByteArrayOutputStream

class DiffService(private val gitOps: GitOperations) {
    //TODO should be singleton
    private var collectConfig: CollectConfiguration = CollectConfiguration().everything()

    /** True if all filters accept, else false. */
    fun diffFiltersAccept(diff: DiffEntry): Boolean =
        collectConfig.diffFilters.any { !it.accept(diff.newPath) }.not()

    fun getDiffBetweenCommits(priorCommit: String, laterCommit: String): List<Modification> {
        return gitOps.git.use { git ->
            runCatching {
                val repo = git.repository
                val prior: AnyObjectId = repo.resolve(priorCommit)
                val later: AnyObjectId = repo.resolve(laterCommit)

                this.getDiffBetweenCommits(repo, prior, later)
                    .map { diff: DiffEntry -> return@map this.diffToModification(repo, diff) }
            }.getOrElse { throw RuntimeException("Error diffing $priorCommit and $laterCommit in ${gitOps.path}", it) }
        }
    }

    fun diffToModification(repo: Repository, diff: DiffEntry): Modification {
        val change = enumValueOf<ModificationType>(diff.changeType.toString())

        val oldPath = diff.oldPath
        val newPath = diff.newPath

        val sourceCode = gitOps.getSourceCode(repo, diff)
        val diffText = if (diff.changeType != DiffEntry.ChangeType.DELETE) {
            getDiffText(repo, diff).let { diffContent ->
                if (diffContent.length > getMaxSizeOfDiff()) "-- TOO BIG --" else diffContent
            }
        } else ""

        return Modification(oldPath, newPath, change, diffText, sourceCode)
    }


    private fun getDiffText(repo: Repository, diff: DiffEntry): String {
        if (!collectConfig.isCollectingDiffs) return ""

        val out = ByteArrayOutputStream()
        try {
            DiffFormatter(out).use { df2 ->
                df2.setRepository(repo)
                df2.format(diff)
                val diffText = out.toString("UTF-8")
                return diffText
            }
        } catch (e: Throwable) {
            return ""
        }
    }

    fun diffsForTheCommit(repo: Repository, commit: RevCommit): List<DiffEntry> {
        val currentCommit: AnyObjectId = repo.resolve(commit.name)
        val parentCommit: AnyObjectId? = if (commit.parentCount > 0) repo.resolve(commit.getParent(0).name) else null

        return this.getDiffBetweenCommits(repo, parentCommit, currentCommit)
    }

    fun getDiffBetweenCommits(
        repo: Repository, parentCommit: AnyObjectId?,
        currentCommit: AnyObjectId
    ): List<DiffEntry> = runCatching {
        DiffFormatter(DisabledOutputStream.INSTANCE).use { df ->
            df.apply {
                setBinaryFileThreshold(2 * 1024) // 2 mb max a file
                setRepository(repo)
                setDiffComparator(RawTextComparator.DEFAULT)
                isDetectRenames = true
                setContext(df)
            }

            if (parentCommit == null) {
                RevWalk(repo).use { rw ->
                    rw.parseCommit(currentCommit).let {
                        df.scan(EmptyTreeIterator(), CanonicalTreeParser(null, rw.objectReader, it.tree))
                    }
                }
            } else {
                df.scan(parentCommit, currentCommit)
            }
        }
    }.getOrElse {
        throw RuntimeException("Error diffing ${parentCommit!!.name} and ${currentCommit.name}", it)
    }

    private fun setContext(df: DiffFormatter) {
        runCatching {
            val context = getSystemProperty("git.diffcontext")
            df.setContext(context)
        }.onFailure { it.printStackTrace() }
    }
}