package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.Table

object CommitMessages: Table("commit_messages") {
    val hash = reference("hash", Commits.hash, CASCADE, CASCADE)
    val projectId = integer("projectId")
    val shortMessage = text("shortMessage")
    val fullMessage = text("fullMessage")
    override val primaryKey = PrimaryKey(hash)

    init {
        uniqueIndex("${tableName}_project_commit", projectId, hash)
    }
}