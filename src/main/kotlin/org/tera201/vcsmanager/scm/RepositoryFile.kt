package org.tera201.vcsmanager.scm

import org.tera201.vcsmanager.util.RDFileUtils.readFile
import java.io.File
import java.util.*

class RepositoryFile(val file: File) {
    fun fileNameEndsWith(suffix: String): Boolean {
        return file.name.lowercase(Locale.getDefault()).endsWith(suffix.lowercase(Locale.getDefault()))
    }

    fun fileNameMatches(regex: String): Boolean {
        return file.name.lowercase(Locale.getDefault()).matches(regex.toRegex())
    }

    val fullName: String
        get() = file.absolutePath

    fun fileNameContains(text: String): Boolean {
        return file.name.lowercase(Locale.getDefault()).contains(text)
    }

    val sourceCode: String
        get() = readFile(file)

    override fun toString(): String {
        return "[" + file.absolutePath + "]"
    }
}
