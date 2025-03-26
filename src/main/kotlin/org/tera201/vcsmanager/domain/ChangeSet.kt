package org.tera201.vcsmanager.domain

import java.text.SimpleDateFormat
import java.util.*

data class ChangeSet(val id: String, val time: Calendar) {

    override fun toString(): String {
        return "[" + id + ", " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(time!!.time) + "]"
    }
}
