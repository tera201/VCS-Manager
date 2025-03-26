package org.tera201.vcsmanager.scm.exceptions

class CheckoutException : Exception {
    constructor(message: String?) : super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
