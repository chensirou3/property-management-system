package com.propertyops.pms.visitor;

import java.time.LocalDateTime;

public interface VisitorAccessAdapter {
    TransitionEvidence transition(String visitNo, String action);
    String code();
    boolean productionConnected();

    record TransitionEvidence(String adapterCode, String action, String evidence,
                              LocalDateTime occurredAt, boolean simulated,
                              boolean productionConnected) {}
}
