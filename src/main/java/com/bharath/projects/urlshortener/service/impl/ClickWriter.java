package com.bharath.projects.urlshortener.service.impl;

import com.bharath.projects.urlshortener.entity.ClickEvent;
import com.bharath.projects.urlshortener.repository.ClickEventRepository;
import com.bharath.projects.urlshortener.repository.ShortLinkRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one click atomically: the {@code click_event} row and the {@code click_count} increment
 * commit together or not at all, so the counter and the event log can never diverge.
 * A separate bean (not a method on the service) so the transactional proxy applies.
 */
@Component
public class ClickWriter {

    private final ClickEventRepository clicks;
    private final ShortLinkRepository links;

    public ClickWriter(ClickEventRepository clicks, ShortLinkRepository links) {
        this.clicks = clicks;
        this.links = links;
    }

    @Transactional
    public void write(ClickEvent event) {
        clicks.save(event);
        if (links.incrementClickCount(event.getShortLinkId()) == 0) {
            // Rolls back the insert above.
            throw new IllegalStateException("Link " + event.getShortLinkId() + " no longer exists");
        }
    }
}
