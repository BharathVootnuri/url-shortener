package com.bharath.projects.urlshortener.exception;

/** No link exists for the code (including codes that are malformed and so can never exist). */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException() {
        super("No link found for the given code");
    }
}
