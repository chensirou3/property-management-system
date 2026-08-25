package com.propertyops.pms.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pms.security")
public class SecurityProperties {
    private String jwtSecret;
    private long accessTokenMinutes = 30;
    private int loginMaxFailures = 5;
    private long loginWindowMinutes = 15;
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
