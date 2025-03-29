package org.tera201.vcsmanager.util

data class FileEntity(
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

    fun add(fileEntity: FileEntity) {
        fileAdded += fileEntity.fileAdded
        fileDeleted += fileEntity.fileDeleted
        fileModified += fileEntity.fileModified
        linesAdded += fileEntity.linesAdded
        linesDeleted += fileEntity.linesDeleted
        linesModified += fileEntity.linesModified
        changes += fileEntity.changes
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