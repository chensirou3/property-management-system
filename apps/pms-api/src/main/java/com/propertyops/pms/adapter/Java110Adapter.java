package com.propertyops.pms.adapter;

import java.util.Map;

public interface Java110Adapter {
    String code();

    boolean enabled();

    Map<String, Object> invoke(String serviceCode, Map<String, Object> payload);
}
