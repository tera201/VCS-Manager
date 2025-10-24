package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.Table

object Branches: Table("branches") {
    val id = integer("id").autoIncrement()
    val projectId = reference("projectId", Projects.id, CASCADE, CASCADE)
    val name = text("name")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("${tableName}_name_project", name, projectId)
    }
}