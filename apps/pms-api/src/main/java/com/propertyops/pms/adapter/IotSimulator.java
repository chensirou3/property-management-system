package com.propertyops.pms.adapter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

@Component
public class IotSimulator implements IotAdapter {
    @Override
    public Reading read(String meterNo) {
        long stable = Integer.toUnsignedLong(meterNo.hashCode());
        BigDecimal value = BigDecimal.valueOf(stable % 100_000).movePointLeft(2);
        return new Reading(meterNo, value, LocalDateTime.now(ZoneOffset.UTC), true);
    }

    @Override
    public String code() {
        return "IOT_SIMULATOR";
    }
}
