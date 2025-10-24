package org.tera201.vcsmanager.db.entities

import org.jetbrains.exposed.v1.core.ResultRow
import org.tera201.vcsmanager.db.tables.Authors
import org.tera201.vcsmanager.db.tables.Commits
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

    constructor(resultRow: ResultRow): this(
        name = resultRow[Commits.hash],
        projectSize = resultRow[Commits.projectSize],
        authorName = resultRow[Authors.name],
        authorEmail = resultRow[Authors.email],
        stability = resultRow[Commits.stability],
        date = resultRow[Commits.date]
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
