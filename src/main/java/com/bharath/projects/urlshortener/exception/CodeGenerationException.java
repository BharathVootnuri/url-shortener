package com.bharath.projects.urlshortener.exception;

/** A unique short code could not be allocated within the configured number of attempts. */
public class CodeGenerationException extends RuntimeException {

    public CodeGenerationException(String message) {
        super(message);
    }
}
