package com.bharath.projects.urlshortener.exception;

/** The analytics time range is not acceptable (reversed, empty, or longer than the allowed maximum). */
public class InvalidRangeException extends RuntimeException {

    public InvalidRangeException(String message) {
        super(message);
    }
}
