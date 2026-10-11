package com.bharath.projects.urlshortener.exception;

/** The submitted URL is missing, malformed, or not allowed. The message is safe to show to clients. */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String message) {
        super(message);
    }
}
