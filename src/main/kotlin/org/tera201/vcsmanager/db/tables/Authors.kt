package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object Authors: Table("authors") {
    val id = long("id")
    val name = text("name")
    val email = text("email",)
    val projectId = reference("projectId", Projects.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("${tableName}_email_project", email, projectId)
    }
}