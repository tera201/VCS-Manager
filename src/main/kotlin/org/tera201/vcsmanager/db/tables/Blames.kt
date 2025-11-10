package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object Blames: Table("blames") {
    val id = integer("id").autoIncrement()
    val projectId = reference("projectId", Projects.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    val authorId = reference("authorId", Authors.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    val blameFileId = reference("blameFileId", BlameFiles.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    val lineIds = text("lineIds")
    val lineCounts = long("lineCounts")
    val lineSize = long("lineSize")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("${tableName}_project_author_blame", projectId, authorId, blameFileId)
    }
}