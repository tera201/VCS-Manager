package org.tera201.vcsmanager.db.entities

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper

data class BlameEntity(
    val projectId: Int,
    val authorId: Long,
    val blameFileId: Int,
    val lineIds: MutableList<Int>,
    var lineSize: Long
) {
    fun getSQLArgs(): Array<Any> = arrayOf(
            projectId,
            authorId,
            blameFileId,
            convertListToJson(lineIds),
            lineIds.size.toLong(),
            lineSize)

    fun getSQLCompressedArgs(): Array<Any> =arrayOf(
        projectId,
        authorId,
        blameFileId,
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
