package com.propertyops.pms.security;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyops.pms.common.api.ApiError;
import com.propertyops.pms.common.api.RequestIdFilter;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object requestIdAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = requestIdAttribute == null
                ? UUID.randomUUID().toString()
                : requestIdAttribute.toString();

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(RequestIdFilter.HEADER, requestId);
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of("UNAUTHORIZED", "身份认证无效或已过期", requestId));
    }
}
