package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.Table

object Projects : Table("projects") {
    val id = integer("id").autoIncrement()
    val name = text("name")
    val path = text("path")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("${tableName}_name_path", name, path)
    }
}