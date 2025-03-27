package org.tera201.vcsmanager

class VCSException : RuntimeException {
    constructor(msg: String?) : super(msg)

    constructor(e: Exception?) : super(e)

    constructor(msg: String?, e: Exception?) : super(msg, e)

    companion object {
        private const val serialVersionUID = 1L
    }
}