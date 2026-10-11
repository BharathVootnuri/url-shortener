package com.bharath.projects.urlshortener.service;

import com.bharath.projects.urlshortener.dto.CreateLinkRequest;
import com.bharath.projects.urlshortener.dto.LinkDetailsResponse;
import com.bharath.projects.urlshortener.dto.LinkResponse;
import com.bharath.projects.urlshortener.dto.RedirectTarget;
import com.bharath.projects.urlshortener.exception.CodeGenerationException;
import com.bharath.projects.urlshortener.exception.InvalidExpiryException;
import com.bharath.projects.urlshortener.exception.InvalidUrlException;
import com.bharath.projects.urlshortener.exception.LinkExpiredException;
import com.bharath.projects.urlshortener.exception.LinkNotFoundException;

public interface LinkService {

    /**
     * Creates a short link.
     *
     * @throws InvalidUrlException     the URL is missing, malformed or not allowed
     * @throws InvalidExpiryException  expiry is not in the future, or is more than 5 years ahead
     * @throws CodeGenerationException no unique code could be allocated within the retry limit
     */
    LinkResponse create(CreateLinkRequest request);

    /**
     * Returns link details. Expired links are still readable (status EXPIRED).
     *
     * @throws LinkNotFoundException unknown or malformed code
     */
    LinkDetailsResponse getDetails(String code);

    /**
     * Resolves a code for redirecting.
     *
     * @throws LinkNotFoundException unknown or malformed code (a malformed code never queries the database)
     * @throws LinkExpiredException  the link exists but has expired
     */
    RedirectTarget resolve(String code);
}
