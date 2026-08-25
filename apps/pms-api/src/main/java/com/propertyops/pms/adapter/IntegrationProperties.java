package com.propertyops.pms.adapter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "pms.integrations")
public class IntegrationProperties {
    @NotBlank
    @Size(min = 32, max = 512)
    private String callbackSigningSecret;

    @Min(30)
    @Max(900)
    private long callbackMaxSkewSeconds = 300;

    public String getCallbackSigningSecret() { return callbackSigningSecret; }
    public void setCallbackSigningSecret(String callbackSigningSecret) { this.callbackSigningSecret = callbackSigningSecret; }
    public long getCallbackMaxSkewSeconds() { return callbackMaxSkewSeconds; }
    public void setCallbackMaxSkewSeconds(long callbackMaxSkewSeconds) { this.callbackMaxSkewSeconds = callbackMaxSkewSeconds; }
}
