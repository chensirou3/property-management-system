package com.propertyops.pms.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.propertyops.pms.common.api.BusinessException;

@Service
public class CallbackSignatureService {
    private final IntegrationProperties properties;

    public CallbackSignatureService(IntegrationProperties properties) {
        this.properties = properties;
    }

    public void verify(String adapterCode, String callbackId, String timestamp, String payload, String supplied) {
        Instant signedAt;
        try {
            signedAt = Instant.parse(timestamp);
        } catch (RuntimeException exception) {
            throw rejected("回调时间戳格式无效");
        }
        long skew = Math.abs(Duration.between(signedAt, Instant.now()).toSeconds());
        if (skew > properties.getCallbackMaxSkewSeconds()) throw rejected("回调时间戳已过期");
        byte[] expected = signBytes(adapterCode, callbackId, timestamp, payload);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(supplied == null ? "" : supplied);
        } catch (IllegalArgumentException exception) {
            throw rejected("回调签名格式无效");
        }
        if (!MessageDigest.isEqual(expected, actual)) throw rejected("回调签名验证失败");
    }

    public String sign(String adapterCode, String callbackId, String timestamp, String payload) {
        return HexFormat.of().formatHex(signBytes(adapterCode, callbackId, timestamp, payload));
    }

    private byte[] signBytes(String adapterCode, String callbackId, String timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getCallbackSigningSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = timestamp + "\n" + adapterCode + "\n" + callbackId + "\n" + payload;
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot compute callback signature", exception);
        }
    }

    private BusinessException rejected(String message) {
        return new BusinessException("CALLBACK_SIGNATURE_REJECTED", message, HttpStatus.UNAUTHORIZED);
    }
}
