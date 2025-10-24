package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.Table

object FilePath: Table("file_path") {
    val id = long("id")
    val projectId = reference("projectId", Projects.id, CASCADE, CASCADE)
    val filePath = text("filePath")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("${tableName}_project_file", projectId, filePath)
    }
}