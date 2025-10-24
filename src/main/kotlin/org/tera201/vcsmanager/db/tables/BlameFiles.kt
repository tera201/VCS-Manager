package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object BlameFiles: Table("blame_files") {
    val id = integer("id").autoIncrement()
    val projectId = reference("projectId", Projects.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val filePathId = reference("filePathId", FilePath.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val fileHash = reference("fileHash", Commits.hash, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val lineSize = long("lineSize").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("${tableName}_project_file", projectId, filePathId, fileHash)
    }
}