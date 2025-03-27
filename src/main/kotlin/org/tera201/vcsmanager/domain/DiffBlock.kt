package org.tera201.vcsmanager.domain

import org.tera201.vcsmanager.VCSException
import java.util.regex.Pattern

class DiffBlock(val diffBlock: String) {
    private val d1: Int
    private val d2: Int
    private val d3: Int
    private val d4: Int

    private val lines: Array<String> = diffBlock.replace("\r", "").split("\n").filter { it.isNotEmpty() }.toTypedArray()

    init {
        val positions = lines[0]
        val p = Pattern.compile("@@ -(\\d*),(\\d*) \\+(\\d*),(\\d*) @@.*")
        val matcher = p.matcher(positions)

        if (matcher.matches()) {
            d1 = matcher.group(1).toInt()
            d2 = matcher.group(2).toInt()
            d3 = matcher.group(3).toInt()
            d4 = matcher.group(4).toInt()
        } else {
            throw VCSException("Impossible to get line positions in this diff: $diffBlock")
        }
    }

    private fun getLines(start: Int, qtyLines: Int, ch: String): List<DiffLine> {
        val oldLines = mutableListOf<DiffLine>()
        var counter = start

        for (line in lines) {
            if (line.startsWith(ch) || line.startsWith(" ")) {
                oldLines.add(DiffLine(counter, line.substring(1), typeOf(line)))
                counter++
            }
        }

        if (counter != start + qtyLines) throw VCSException("Malformed diff")
        return oldLines
    }

    private fun typeOf(line: String): DiffLineType =
        when {
            line.startsWith(" ") -> DiffLineType.KEPT
            line.startsWith("+") -> DiffLineType.ADDED
            line.startsWith("-") -> DiffLineType.REMOVED
            else -> throw VCSException("Type of diff line not recognized: $line")
        }

    val linesInOldFile: List<DiffLine> = getLines(d1, d2, "-")

    fun getLineInOldFile(line: Int): DiffLine? = linesInOldFile.find { it.lineNumber == line }

    fun getLineInNewFile(line: Int): DiffLine? = linesInNewFile.find { it.lineNumber == line }

    val linesInNewFile: List<DiffLine> = getLines(d3, d4, "+")
}