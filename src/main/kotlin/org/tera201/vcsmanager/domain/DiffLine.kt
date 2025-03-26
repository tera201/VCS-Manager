package org.tera201.vcsmanager.domain

data class DiffLine(val lineNumber: Int, val line: String, val type: DiffLineType) {
    override fun toString(): String {
        return "DiffLine [lineNumber=$lineNumber, line=$line, type=$type]"
    }
}
