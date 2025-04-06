package org.tera201.vcsmanager.db.entities

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit

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

    fun applyChanges(diff: DiffEntry, diffFormatter: DiffFormatter, diffSize: Int) {
        var fileAdded = 0
        var fileDeleted = 0
        var fileModified = 0
        var linesAdded = 0
        var linesDeleted = 0
        var linesModified = 0
        var changes = 0

        when (diff.changeType) {
            DiffEntry.ChangeType.ADD -> fileAdded++
            DiffEntry.ChangeType.DELETE -> fileDeleted++
            DiffEntry.ChangeType.MODIFY, DiffEntry.ChangeType.RENAME -> fileModified++
            DiffEntry.ChangeType.COPY -> fileAdded++
        }

        diffFormatter.toFileHeader(diff).toEditList().forEach { edit ->
            when (edit.type) {
                Edit.Type.INSERT -> {
                    linesAdded += edit.lengthB
                    changes += edit.lengthB
                }

                Edit.Type.DELETE -> {
                    linesDeleted += edit.lengthA
                    changes += edit.lengthA
                }

                Edit.Type.REPLACE -> {
                    linesModified += edit.lengthA + edit.lengthB
                    changes += edit.lengthA + edit.lengthB
                }

                Edit.Type.EMPTY -> return@forEach
            }
        }

        plus(fileAdded, fileDeleted, fileModified, linesAdded, linesDeleted, linesModified, changes, diffSize)
    }
}