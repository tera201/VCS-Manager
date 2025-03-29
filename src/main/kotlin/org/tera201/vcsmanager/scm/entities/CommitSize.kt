package org.tera201.vcsmanager.scm.entities

import java.sql.ResultSet

data class CommitSize (
    val name: String,
    var projectSize: Long = 0,
    var authorName: String? = null,
    var authorEmail: String? = null,
    val fileSize: MutableMap<String, Long> = mutableMapOf(),
    var stability:Double = 0.0,
    val date: Int,
) {
    constructor(resultSet: ResultSet): this(
        name = resultSet.getString("hash"),
        projectSize = resultSet.getLong("projectSize"),
        authorName = resultSet.getString("authorName"),
        authorEmail = resultSet.getString("authorEmail"),
        stability = resultSet.getDouble("stability"),
        date = resultSet.getInt("date")
    )

    fun setAuthor(authorName: String?, authorEmail: String?) {
        this.authorName = authorName
        this.authorEmail = authorEmail
    }

    fun addFile(fileName: String, fileSize: Long) {
        this.fileSize[fileName] = fileSize
        this.projectSize += fileSize
    }

    fun addFileSize(fileSize: Long) = projectSize.plus(fileSize)
}
