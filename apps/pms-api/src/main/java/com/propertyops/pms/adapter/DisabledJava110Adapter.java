package com.propertyops.pms.adapter;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.propertyops.pms.common.api.BusinessException;

@Component
public class DisabledJava110Adapter implements Java110Adapter {
    @Override
    public String code() {
        return "JAVA110_DISABLED";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Map<String, Object> invoke(String serviceCode, Map<String, Object> payload) {
        throw new BusinessException("JAVA110_ADAPTER_DISABLED",
                "Java110 兼容适配器默认禁用，未配置受控上游连接", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
