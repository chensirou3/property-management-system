package com.propertyops.pms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyops.pms.common.api.RequestIdFilter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class RestAuthenticationEntryPointTest {
    @Test
    void writesConsistentUnauthorizedJsonWithRequestId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "test-request-401");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("missing token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("test-request-401");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"requestId\":\"test-request-401\"");
    }
}
