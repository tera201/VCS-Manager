package org.tera201.vcsmanager.util

data class BlameEntity(val projectId:Int, val authorId:String, val blameFileId:Int, val blameHashes:MutableList<String>, val lineIds:MutableList<Int>, var lineSize:Long)
