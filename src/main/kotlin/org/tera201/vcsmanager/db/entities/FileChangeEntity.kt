package org.tera201.vcsmanager.db.entities

data class FileChangeEntity(
    var fileAdded: Int = 0,
    var fileDeleted: Int = 0,
    var fileModified: Int = 0,
    var linesAdded: Int = 0,
    var linesDeleted: Int = 0,
    var linesModified: Int = 0,
    var changes: Int = 0,
    var changesSize: Int = 0
) {

    fun getSQLArgs():Array<Any> {
        return arrayOf(fileAdded, fileDeleted, fileModified, linesAdded, linesDeleted, linesModified, changes, changesSize)
    }

    fun add(fileChangeEntity: FileChangeEntity) {
        fileAdded += fileChangeEntity.fileAdded
        fileDeleted += fileChangeEntity.fileDeleted
        fileModified += fileChangeEntity.fileModified
        linesAdded += fileChangeEntity.linesAdded
        linesDeleted += fileChangeEntity.linesDeleted
        linesModified += fileChangeEntity.linesModified
        changes += fileChangeEntity.changes
    }

    fun plus(
        fileAdded: Int,
        fileDeleted: Int,
        fileModified: Int,
        linesAdded: Int,
        linesDeleted: Int,
        linesModified: Int,
        changes: Int,
        changesSize: Int
    ) {
        this.fileAdded += fileAdded
        this.fileDeleted += fileDeleted
        this.fileModified += fileModified
        this.linesAdded += linesAdded
        this.linesDeleted += linesDeleted
        this.linesModified += linesModified
        this.changes += changes
        this.changesSize += changesSize
    }
}