package org.tera201.vcsmanager.scm.entities

data class FileEntity(
    val projectId: Int,
    val filePath: String,
    val filePathId: Long,
    val hash: String,
    val date: Int
) {
    fun getSQLArgs(): Array<Any> =  arrayOf(projectId, filePathId, hash, date)
}