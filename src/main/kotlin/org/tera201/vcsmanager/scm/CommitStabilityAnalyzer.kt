package org.tera201.vcsmanager.scm

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.util.*
import kotlin.math.max
import kotlin.math.min

object CommitStabilityAnalyzer {
    fun analyzeCommit(git: Git, commitList: List<RevCommit>, commit: RevCommit, index: Int): Double {
        var commitStability = 1.0
        val commitDate = commit.committerIdent.getWhen()
        val calendar = Calendar.getInstance()
        calendar.time = commitDate
        calendar.add(Calendar.MONTH, 1)
        val oneMonthLater = calendar.time

        val nextMonthCommit = findCommitInNextMonth(commitList, index, commitDate, oneMonthLater)

        if (nextMonthCommit != null) {
            commitStability = calculateCommitStability(git, commit, nextMonthCommit)
        }
        return commitStability
    }

    private fun findCommitInNextMonth(
        commitList: List<RevCommit>,
        index: Int,
        currentCommitDate: Date,
        oneMonthLater: Date
    ): RevCommit? {
        var nextMonthCommits: RevCommit? = null
        for (i in 0..<index) {
            val commit = commitList[i]
            val commitDate = commit.committerIdent.getWhen()
            if (commitDate.after(currentCommitDate) && commitDate.before(oneMonthLater)) {
                nextMonthCommits = commit
            }
        }
        return nextMonthCommits
    }

    private fun calculateCommitStability(git: Git, targetCommit: RevCommit, lastMonthCommit: RevCommit): Double {
        val parent = if (targetCommit.parentCount > 0) targetCommit.getParent(0) else null
        val editsAB: MutableList<Edit> = ArrayList()
        val editsBC: MutableList<Edit> = ArrayList()
        val abSize: Long

        DiffFormatter(DisabledOutputStream.INSTANCE).use { diffFormatter ->
            diffFormatter.setRepository(git.repository)
            var diffs = diffFormatter.scan(parent, targetCommit)
            for (diff in diffs) {
                val fileHeader = diffFormatter.toFileHeader(diff)
                val edits = fileHeader.toEditList()
                editsAB.addAll(edits)
            }

            abSize = editsAB.stream().mapToLong { obj: Edit -> obj.lengthB.toLong().toLong() }.sum()
            if (abSize == 0L) return 0.0

            diffs = diffFormatter.scan(targetCommit, lastMonthCommit)
            for (diff in diffs) {
                val fileHeader = diffFormatter.toFileHeader(diff)
                val edits = fileHeader.toEditList()
                editsBC.addAll(edits)
            }
        }
        val intersectionSize =
            getIntersectingEdits(editsAB, editsBC).stream().mapToDouble { it: IntArray -> (it[1] - it[0]).toDouble() }
                .sum()

        return 1 - intersectionSize / abSize
    }


    private fun getIntersectionStartAndEnd(editA: Edit, editB: IntArray): IntArray {
        val start = max(editA.beginB.toDouble(), editB[0].toDouble()).toInt()
        val end = min(editA.endB.toDouble(), editB[1].toDouble()).toInt()

        return if (start < end) {
            intArrayOf(start, end)
        } else {
            IntArray(0)
        }
    }

    fun mergeIntersectingRanges(edits: List<Edit>): List<IntArray> {
        if (edits.isEmpty()) return emptyList()
        val sortedEdits = edits.sortedBy { it.beginA }
        val mergedRanges: MutableList<IntArray> = ArrayList()
        var currentRange = intArrayOf(edits[0].beginA, edits[0].endA)

        for (edit in sortedEdits) {
            val startA = edit.beginA
            val endA = edit.endA

            if (startA <= currentRange[1]) {
                currentRange[1] = max(currentRange[1].toDouble(), endA.toDouble()).toInt()
            } else {
                mergedRanges.add(currentRange)
                currentRange = intArrayOf(startA, endA)
            }
        }
        mergedRanges.add(currentRange)
        return mergedRanges
    }

    private fun getIntersectingEdits(editsA: List<Edit>, editsB: List<Edit>): List<IntArray> {
        val intersectingEdits: MutableList<IntArray> = ArrayList()
        val mergedEditsB = mergeIntersectingRanges(editsB)
        for (editA in editsA) {
            for (editB in mergedEditsB) {
                val intersection = getIntersectionStartAndEnd(editA, editB)
                if (intersection.size != 0) {
                    intersectingEdits.add(intersection)
                }
            }
        }
        return intersectingEdits
    }
}