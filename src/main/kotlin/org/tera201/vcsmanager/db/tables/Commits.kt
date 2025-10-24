package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.Table

object Commits: Table("commits") {
    val hash = text("hash")
    val date = integer("date")
    val projectSize = long("projectSize")
    val projectId = reference("projectId", Projects.id, CASCADE, CASCADE)
    val authorId = reference("authorId", Authors.id, CASCADE, CASCADE)
    val stability = double("stability")
    val filesAdded = integer("filesAdded")
    val filesDeleted = integer("filesDeleted")
    val filesModified = integer("filesModified")
    val linesAdded = integer("linesAdded")
    val linesDeleted = integer("linesDeleted")
    val linesModified = integer("linesModified")
    val changes = integer("changes")
    val changesSize = integer("changesSize")
    override val primaryKey = PrimaryKey(hash)

    init {
        uniqueIndex("${tableName}_project_commit", projectId, hash)
    }
}