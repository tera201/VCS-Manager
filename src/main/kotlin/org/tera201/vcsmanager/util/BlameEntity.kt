package org.tera201.vcsmanager.util

data class BlameEntity(val projectId:Int, val authorId:String, val blameFileId:Int, val blameHashes:List<String>, val lineIds:List<Int>, var lineSize:Long)
