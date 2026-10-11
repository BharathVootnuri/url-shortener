package com.bharath.projects.urlshortener.exception;

/** The request body exceeds the allowed size. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(long maxBytes) {
        super("Request body must not exceed " + maxBytes + " bytes");
    }
}
