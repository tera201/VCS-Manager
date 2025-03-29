package org.tera201.vcsmanager.util

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.tera201.vcsmanager.util.SQLiteCommon.Companion.compress

data class BlameEntity(
    val projectId: Int,
    val authorId: String,
    val blameFileId: Int,
    val blameHashes: MutableList<String>,
    val lineIds: MutableList<Int>,
    var lineSize: Long
) {
    fun getSQLArgs(): Array<Any> = arrayOf(
            projectId,
            authorId,
            blameFileId,
            convertListToJson(blameHashes),
            convertListToJson(lineIds),
            lineIds.size.toLong(),
            lineSize)

    fun getSQLCompressedArgs(): Array<Any> =arrayOf(
        projectId,
        authorId,
        blameFileId,
        compress(convertListToJson(blameHashes)),
        convertListToJson(lineIds),
        lineIds.size.toLong(),
        lineSize)

    private fun convertListToJson(list: List<Any>): String {
        val mapper = ObjectMapper()
        var jsonString: String = "{}"
        try {
            jsonString = mapper.writeValueAsString(list)
        } catch (e: JsonProcessingException) {
            e.printStackTrace()
        }
        return jsonString
    }
}
