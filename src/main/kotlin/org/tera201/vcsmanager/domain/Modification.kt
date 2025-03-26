package org.tera201.vcsmanager.domain

import java.io.File
import java.util.*

data class Modification(
    val oldPath: String,
    val newPath: String?,
    val type: ModificationType,
    val diff: String,
    val sourceCode: String
) {
    var added: Int = 0
        private set
    var removed: Int = 0
        private set

    init {
        diff.replace("\r", "").split("\n").forEach { line ->
            when {
                line.startsWith("+") && !line.startsWith("+++") -> added++
                line.startsWith("-") && !line.startsWith("---") -> removed++
            }
        }
    }

    override fun toString(): String {
        return ("Modification [oldPath=" + oldPath + ", newPath=" + newPath + ", type=" + type
                + "]")
    }

    fun wasDeleted(): Boolean = type == ModificationType.DELETE

    fun fileNameEndsWith(suffix: String): Boolean =
        newPath?.lowercase(Locale.getDefault())?.endsWith(suffix.lowercase(Locale.getDefault())) ?: false

    fun fileNameMatches(regex: String): Boolean =
        newPath?.lowercase(Locale.getDefault())?.matches(regex.toRegex()) ?: false

    val fileName: String
        get() {
            val thePath = newPath?.takeIf { it != "/dev/null" } ?: oldPath
            return if (!thePath.contains(File.separator)) thePath else thePath.split(File.separator).last()
        }
}
