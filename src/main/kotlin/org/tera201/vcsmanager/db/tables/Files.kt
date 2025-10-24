package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.Table

object Files: Table("files") {
    val id = integer("id").autoIncrement()
    val projectId = reference("projectId", Projects.id, CASCADE, CASCADE)
    val filePathId = reference("filePathId", FilePath.id, CASCADE, CASCADE)
    val hash = reference("hash", Commits.hash, CASCADE, CASCADE)
    val date = integer("date")
    override val primaryKey = PrimaryKey(id)
}