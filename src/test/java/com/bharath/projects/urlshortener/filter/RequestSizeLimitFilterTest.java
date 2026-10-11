package com.bharath.projects.urlshortener.filter;

import com.bharath.projects.urlshortener.exception.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestSizeLimitFilterTest {

    private final AtomicReference<Exception> resolved = new AtomicReference<>();
    private final HandlerExceptionResolver resolver = (req, res, handler, ex) -> {
        resolved.set(ex);
        return null;
    };
    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(resolver);

    /** A request that claims no Content-Length, like a chunked upload. */
    private static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/short-urls") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }

    @Test
    void rejectsDeclaredContentLengthOverTheLimitWithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/short-urls");
        request.setContent(new byte[(int) RequestSizeLimitFilter.MAX_BODY_BYTES + 1]);
        boolean[] chainCalled = {false};

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isFalse();
        assertThat(resolved.get()).isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void acceptsBodyAtTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/short-urls");
        request.setContent(new byte[(int) RequestSizeLimitFilter.MAX_BODY_BYTES]);
        int[] read = {0};

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> read[0] = ((HttpServletRequest) req).getInputStream().readAllBytes().length);

        assertThat(read[0]).isEqualTo((int) RequestSizeLimitFilter.MAX_BODY_BYTES);
        assertThat(resolved.get()).isNull();
    }

    @Test
    void cutsOffChunkedBodiesThatKeepGoingPastTheLimit() {
        MockHttpServletRequest request = chunked(new byte[(int) RequestSizeLimitFilter.MAX_BODY_BYTES * 4]);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> ((HttpServletRequest) req).getInputStream().readAllBytes()))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void doesNotCutOffSmallChunkedBodies() throws Exception {
        byte[] body = "{\"url\":\"https://example.com\"}".getBytes();
        MockHttpServletRequest request = chunked(body);
        int[] read = {0};

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> read[0] = ((HttpServletRequest) req).getInputStream().readAllBytes().length);

        assertThat(read[0]).isEqualTo(body.length);
    }

    @Test
    void ignoresRequestsWithoutBodies() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/abc"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/short-urls"))).isFalse();
    }
}
