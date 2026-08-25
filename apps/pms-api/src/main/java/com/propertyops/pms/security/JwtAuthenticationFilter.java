package com.propertyops.pms.security;

import java.io.IOException;
import java.util.stream.Collectors;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyops.pms.common.api.ApiError;
import com.propertyops.pms.common.api.RequestIdFilter;
import com.propertyops.pms.iam.AuthRepository;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final AuthRepository authRepository;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, AuthRepository authRepository, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.authRepository = authRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtService.VerifiedToken verified = jwtService.verify(header.substring(7));
                AuthRepository.SessionState session = authRepository.findSessionState(verified.principal().userId())
                        .filter(value -> value.enabled() && value.sessionVersion() == verified.sessionVersion())
                        .orElse(null);
                if (session == null) {
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                AuthPrincipal principal = verified.principal();
                var authorities = principal.authorities().stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, authorities));
                if (session.passwordChangeRequired() && passwordChangeBlocked(request)) {
                    writePasswordChangeRequired(request, response);
                    return;
                }
            } catch (JWTVerificationException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private boolean passwordChangeBlocked(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/v1/")) return false;
        return !request.getRequestURI().equals("/api/v1/auth/me")
                && !request.getRequestURI().equals("/api/v1/auth/change-password")
                && !request.getRequestURI().equals("/api/v1/auth/sessions:revoke");
    }

    private void writePasswordChangeRequired(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = value == null ? "unknown" : value.toString();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(RequestIdFilter.HEADER, requestId);
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of("PASSWORD_CHANGE_REQUIRED", "必须先修改临时密码", requestId));
    }
}
