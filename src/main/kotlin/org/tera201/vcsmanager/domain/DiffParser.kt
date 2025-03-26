package org.tera201.vcsmanager.domain

import java.util.*

class DiffParser(val fullDiff: String) {
    val diffBlocks: MutableList<DiffBlock> = ArrayList()

    init {
        extractDiffBlocks()
    }

    private fun extractDiffBlocks() {
        val lines = fullDiff.split("\n").filterNot { it.isEmpty() || it.startsWith("\r") }
        val linesNoHeader = lines.drop(4)

        var currentDiff = StringBuilder()
        var currentInADiff = false

        for (line in linesNoHeader) {
            when {
                line.startsWith("@@ -") && !currentInADiff -> {
                    currentInADiff = true
                }
                line.startsWith("@@ -") && currentInADiff -> {
                    diffBlocks.add(DiffBlock(currentDiff.toString()))
                    currentDiff = StringBuilder()
                    currentInADiff = false
                }
            }

            if (currentInADiff) {
                currentDiff.append("$line\n")
            }
        }
        diffBlocks.add(DiffBlock(currentDiff.toString()))
    }
}
