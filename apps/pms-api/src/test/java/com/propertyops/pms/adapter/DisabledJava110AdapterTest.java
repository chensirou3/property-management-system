package com.propertyops.pms.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.propertyops.pms.common.api.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DisabledJava110AdapterTest {
    private final DisabledJava110Adapter adapter = new DisabledJava110Adapter();

    @Test
    void isExplicitlyDisabledAndCannotCallUpstream() {
        assertThat(adapter.enabled()).isFalse();
        assertThatThrownBy(() -> adapter.invoke("serviceCode", Map.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
