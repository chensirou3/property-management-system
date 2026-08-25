package com.propertyops.pms.visitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class SimulatedVisitorAccessAdapter implements VisitorAccessAdapter {
    @Override
    public TransitionEvidence transition(String visitNo, String action) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String evidence = "simulated:" + HexFormat.of().formatHex(sha256(visitNo + "|" + action + "|" + now)).substring(0, 24);
        return new TransitionEvidence(code(), action, evidence, now, true, false);
    }

    @Override
    public String code() {
        return "IOT_SIMULATOR";
    }

    @Override
    public boolean productionConnected() {
        return false;
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
