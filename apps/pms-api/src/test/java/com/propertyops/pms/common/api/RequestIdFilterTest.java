package com.propertyops.pms.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void keepsSafeCallerRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "ui-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo("ui-request-123");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("ui-request-123");
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "bad id\nheader");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertThat(generated).matches("[0-9a-f-]{36}");
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(generated);
    }
}
