package com.propertyops.pms.adapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface IotAdapter {
    Reading read(String meterNo);
    String code();

    record Reading(String meterNo, BigDecimal value, LocalDateTime readAt, boolean simulated) {}
}
