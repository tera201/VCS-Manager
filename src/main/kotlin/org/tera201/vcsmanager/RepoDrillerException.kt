package org.tera201.vcsmanager

class RepoDrillerException : RuntimeException {
    constructor(msg: String?) : super(msg)

    constructor(e: Exception?) : super(e)

    companion object {
        private const val serialVersionUID = 1L
    }
}