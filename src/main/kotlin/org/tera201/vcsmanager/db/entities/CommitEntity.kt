package org.tera201.vcsmanager.db.entities

import org.jetbrains.exposed.v1.core.ResultRow
import org.tera201.vcsmanager.db.tables.Authors
import org.tera201.vcsmanager.db.tables.CommitMessages
import org.tera201.vcsmanager.db.tables.Commits
import java.sql.ResultSet

data class CommitEntity(
    val projectId: Int,
    val authorId: Long,
    val authorName: String,
    val authorEmail: String,
    val shortMessage: String,
    val fullMessage: String,
    val hash: String,
    val date: Int,
    val projectSize: Long,
    val stability: Double,
    val fileChangeEntity: FileChangeEntity
) {
    constructor(resultSet: ResultSet) : this(
        projectId = resultSet.getInt("projectId"),
        authorId = resultSet.getLong("authorId"),
        authorName = resultSet.getString("authorName"),
        authorEmail = resultSet.getString("authorEmail"),
        shortMessage = resultSet.getString("shortMessage"),
        fullMessage = resultSet.getString("fullMessage"),
        hash = resultSet.getString("hash"),
        date = resultSet.getInt("date"),
        projectSize = resultSet.getLong("projectSize"),
        stability = resultSet.getDouble("stability"),
        fileChangeEntity = FileChangeEntity(
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

    constructor(resultRow: ResultRow) : this(
        projectId = resultRow[Commits.projectId],
        authorId = resultRow[Authors.id],
        authorName = resultRow[Authors.name],
        authorEmail = resultRow[Authors.email],
        shortMessage = resultRow[CommitMessages.shortMessage],
        fullMessage = resultRow[CommitMessages.fullMessage],
        hash = resultRow[Commits.hash],
        date = resultRow[Commits.date],
        projectSize = resultRow[Commits.projectSize],
        stability = resultRow[Commits.stability],
        fileChangeEntity = FileChangeEntity(
            fileAdded = resultRow[Commits.filesAdded],
            fileDeleted = resultRow[Commits.filesDeleted],
            fileModified = resultRow[Commits.filesModified],
            linesAdded = resultRow[Commits.linesAdded],
            linesDeleted = resultRow[Commits.linesDeleted],
            linesModified = resultRow[Commits.linesModified],
            changes = resultRow[Commits.changes],
            changesSize = resultRow[Commits.changesSize]
        )
    )
}
