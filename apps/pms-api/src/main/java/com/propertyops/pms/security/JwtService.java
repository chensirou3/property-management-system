package com.propertyops.pms.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecurityProperties properties;
    private final Clock clock;
    private final Algorithm algorithm;

    @Autowired
    public JwtService(SecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtService(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        String secret = properties.getJwtSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 characters");
        }
        this.algorithm = Algorithm.HMAC256(secret);
    }

    public Token issue(AuthPrincipal principal) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenMinutes(), ChronoUnit.MINUTES);
        String value = JWT.create()
                .withIssuer("property-ops-pms")
                .withSubject(principal.userId())
                .withIssuedAt(Date.from(issuedAt))
                .withExpiresAt(Date.from(expiresAt))
                .withClaim("username", principal.username())
                .withClaim("displayName", principal.displayName())
                .withArrayClaim("roles", principal.roles().toArray(String[]::new))
                .withArrayClaim("permissions", principal.permissions().toArray(String[]::new))
                .withArrayClaim("projectIds", principal.projectIds().toArray(String[]::new))
                .sign(algorithm);
        return new Token(value, expiresAt);
    }

    public AuthPrincipal verify(String token) throws JWTVerificationException {
        JWTVerifier.BaseVerification verification = (JWTVerifier.BaseVerification) JWT.require(algorithm);
        verification.withIssuer("property-ops-pms");
        DecodedJWT jwt = verification.build(clock).verify(token);
        return new AuthPrincipal(
                jwt.getSubject(),
                jwt.getClaim("username").asString(),
                jwt.getClaim("displayName").asString(),
                set(jwt.getClaim("roles").asList(String.class)),
                set(jwt.getClaim("permissions").asList(String.class)),
                set(jwt.getClaim("projectIds").asList(String.class))
        );
    }

    private Set<String> set(List<String> values) {
        return values == null ? Set.of() : new LinkedHashSet<>(values);
    }

    public record Token(String value, Instant expiresAt) {}
}
