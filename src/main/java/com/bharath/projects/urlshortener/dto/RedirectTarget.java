package com.bharath.projects.urlshortener.dto;

/** What the redirect endpoint needs: where to send the user and which link to credit the click to. */
public record RedirectTarget(Long linkId, String url) {
}
