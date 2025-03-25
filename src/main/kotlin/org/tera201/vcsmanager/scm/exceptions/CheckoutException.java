package org.tera201.vcsmanager.scm.exceptions;

public class CheckoutException extends Exception {
    public CheckoutException(String message) {
        super(message);
    }

    public CheckoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
