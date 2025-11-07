package org.tera201.vcsmanager.scm.services

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.util.*
import kotlin.math.max
import kotlin.math.min

object CommitStabilityAnalyzer {
    private val diffFormatterThreadLocal = ThreadLocal.withInitial {
        DiffFormatter(DisabledOutputStream.INSTANCE)
    }

    fun analyzeCommit(git: Git, commitList: List<RevCommit>, commit: RevCommit, index: Int): Double {
        var commitStability = 1.0
        val commitDate = commit.committerIdent.getWhen()
        val calendar = Calendar.getInstance()
        calendar.time = commitDate
        calendar.add(Calendar.MONTH, 1)
        val oneMonthLater = calendar.time

        val nextMonthCommit = findCommitInNextMonth(commitList, index, commitDate, oneMonthLater)

        if (nextMonthCommit != null) {
            commitStability = calculateCommitStabilityStricter(git, commit, nextMonthCommit)
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

    @Deprecated("Use calculateCommitStabilityStricter instead")
    private fun calculateCommitStability(git: Git, targetCommit: RevCommit, lastMonthCommit: RevCommit): Double {
        val parent = if (targetCommit.parentCount > 0) targetCommit.getParent(0) else return 1.0
        if (targetCommit.tree == null) return 1.0
        val editsAB: MutableList<Edit> = ArrayList()
        val editsBC: MutableList<Edit> = ArrayList()
        var abSize = 0L

        val diffFormatter = diffFormatterThreadLocal.get()
        diffFormatter.setRepository(git.repository)
        for (diff in diffFormatter.scan(parent, targetCommit)) {
            val edits = diffFormatter.toFileHeader(diff).toEditList()
            editsAB.addAll(edits)
            abSize += edits.sumOf { it.lengthB.toLong() }
        }

        if (abSize == 0L) return 0.0

        var intersectionSize = 0.0
        for (diff in diffFormatter.scan(targetCommit, lastMonthCommit)) {
            val edits = diffFormatter.toFileHeader(diff).toEditList()
            editsBC.addAll(edits)
        }

        val mergedB = mergeIntersectingRanges(editsBC)
        for (editA in editsAB) {
            for (rangeB in mergedB) {
                val start = max(editA.beginB, rangeB[0])
                val end = min(editA.endB, rangeB[1])
                if (start < end) {
                    intersectionSize += (end - start)
                }
            }
        }

        return 1 - intersectionSize / abSize
    }

    private fun calculateCommitStabilityStricter(git: Git, targetCommit: RevCommit, lastMonthCommit: RevCommit): Double {
        val parent = if (targetCommit.parentCount > 0) targetCommit.getParent(0) else return 1.0
        if (targetCommit.tree == null) return 1.0
        var abSize = 0L
        var intersectionSize = 0.0

        val diffFormatter = diffFormatterThreadLocal.get()
        diffFormatter.setRepository(git.repository)

        // Get diffs for the original commit (parent → target)
        val diffsAB = diffFormatter.scan(parent, targetCommit)

        // Get diffs for the next month (target → nextMonth)
        val diffsBC = diffFormatter.scan(targetCommit, lastMonthCommit)

        for (diffAB in diffsAB) {
            val pathA = diffAB.newPath
            val matchingDiffBC = diffsBC.find { it.newPath == pathA || it.oldPath == pathA } ?: continue
            val editsAB = diffFormatter.toFileHeader(diffAB).toEditList()
            val editsBC = diffFormatter.toFileHeader(matchingDiffBC).toEditList()

            if (editsAB.isEmpty() || editsBC.isEmpty()) continue
            abSize += editsAB.sumOf { it.lengthB.toLong() }
            intersectionSize += getIntersectingEdits(editsAB, editsBC)
                .sumOf { (it[1] - it[0]).toDouble() }
        }

        return if (abSize == 0L) {
            1.0 // fully stable, no overlapping edits
        } else {
            val value = 1 - (intersectionSize / abSize)
            if (value.isNaN() || value.isInfinite()) 1.0 else value
        }
    }

    private fun getIntersectingEdits(editsA: List<Edit>, editsB: List<Edit>): List<IntArray> {
        val intersectingEdits = mutableListOf<IntArray>()
        val mergedEditsB = mergeIntersectingRanges(editsB)

        for (editA in editsA) {
            for (editB in mergedEditsB) {
                // Only compare within the target file’s coordinate space (B)
                val start = max(editA.beginB, editB[0])
                val end = min(editA.endB, editB[1])
                if (start < end) intersectingEdits.add(intArrayOf(start, end))
            }
        }
        return intersectingEdits
    }

    private fun mergeIntersectingRanges(edits: List<Edit>): List<IntArray> {
        if (edits.isEmpty()) return emptyList()
        val sorted = edits.sortedBy { it.beginB }
        val mergedRanges = mutableListOf<IntArray>()
        var currentRange = intArrayOf(sorted[0].beginB, sorted[0].endB)

        for (edit in sorted.drop(1)) {
            if (edit.beginB <= currentRange[1]) {
                currentRange[1] = max(currentRange[1], edit.endB)
            } else {
                mergedRanges.add(currentRange)
                currentRange = intArrayOf(edit.beginB, edit.endB)
            }
        }
        mergedRanges.add(currentRange)
        return mergedRanges
    }
}