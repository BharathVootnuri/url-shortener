package com.bharath.projects.urlshortener.exception;

/** The link exists but its expiration time has passed. */
public class LinkExpiredException extends RuntimeException {

    public LinkExpiredException() {
        super("This link has expired");
    }
}
