package com.holdlatch.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String suppliedId, AtomicReference<String> seenInsideRequest) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (suppliedId != null) {
            request.addHeader(RequestIdFilter.HEADER, suppliedId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInsideRequest.set(MDC.get("requestId"));
            }
        });
        return response.getHeader(RequestIdFilter.HEADER);
    }

    @Test
    void generatesAnIdShowsItInLogsAndReturnsItInTheHeader() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String header = run(null, seen);
        assertNotNull(header);
        assertEquals(header, seen.get());
        assertNull(MDC.get("requestId"), "the id must not leak into the next request handled by this thread");
    }

    @Test
    void honoursASensibleCallerSuppliedId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        assertEquals("trace-abc.123_x", run("trace-abc.123_x", seen));
        assertEquals("trace-abc.123_x", seen.get());
    }

    @Test
    void replacesIdsThatCouldForgeLogLinesOrAreAbsurdlyLong() throws Exception {
        for (String bad : new String[] {"line1\nFAKE LOG ENTRY", "has space", "x".repeat(65), "<script>"}) {
            AtomicReference<String> seen = new AtomicReference<>();
            String header = run(bad, seen);
            assertTrue(!header.equals(bad), "must not echo: " + bad);
            assertTrue(header.matches("[0-9a-f-]{36}"));
        }
    }
}
