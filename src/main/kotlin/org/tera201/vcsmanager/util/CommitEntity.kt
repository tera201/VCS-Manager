package org.tera201.vcsmanager.util

import java.sql.ResultSet

data class CommitEntity(
    val projectId: Int,
    val authorId: Long,
    val authorName: String,
    val authorEmail: String,
    val hash: String,
    val date: Int,
    val projectSize: Long,
    val stability: Double,
    val fileEntity: FileEntity
) {
    constructor(resultSet: ResultSet) : this(
        projectId = resultSet.getInt("projectId"),
        authorId = resultSet.getLong("authorId"),
        authorName = resultSet.getString("authorName"),
        authorEmail = resultSet.getString("authorEmail"),
        hash = resultSet.getString("hash"),
        date = resultSet.getInt("date"),
        projectSize = resultSet.getLong("projectSize"),
        stability = resultSet.getDouble("stability"),
        fileEntity = FileEntity(
            fileAdded = resultSet.getInt("filesAdded"),
            fileDeleted = resultSet.getInt("filesDeleted"),
            fileModified = resultSet.getInt("filesModified"),
            linesAdded = resultSet.getInt("linesAdded"),
            linesDeleted = resultSet.getInt("linesDeleted"),
            linesModified = resultSet.getInt("linesModified"),
            changes = resultSet.getInt("changes"),
            changesSize = resultSet.getInt("changesSize")
        )
    )
}
