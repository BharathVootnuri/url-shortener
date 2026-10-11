package com.bharath.projects.urlshortener.exception;

/** The requested expiration timestamp is not acceptable (in the past or too far ahead). */
public class InvalidExpiryException extends RuntimeException {

    public InvalidExpiryException(String message) {
        super(message);
    }
}
