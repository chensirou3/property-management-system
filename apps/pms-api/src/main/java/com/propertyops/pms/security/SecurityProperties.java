package com.propertyops.pms.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "pms.security")
@Validated
public class SecurityProperties {
    private String jwtSecret;
    private long accessTokenMinutes = 30;
    @Min(1)
    @Max(20)
    private int loginMaxFailures = 5;
    @Min(1)
    @Max(1440)
    private long loginWindowMinutes = 15;
    @Min(1)
    @Max(10080)
    private long loginLockMinutes = 15;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getAccessTokenMinutes() {
        return accessTokenMinutes;
    }

    public void setAccessTokenMinutes(long accessTokenMinutes) {
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public long getLoginWindowMinutes() {
        return loginWindowMinutes;
    }

    public void setLoginWindowMinutes(long loginWindowMinutes) {
        this.loginWindowMinutes = loginWindowMinutes;
    }

    public long getLoginLockMinutes() {
        return loginLockMinutes;
    }

    public void setLoginLockMinutes(long loginLockMinutes) {
        this.loginLockMinutes = loginLockMinutes;
    }
}
