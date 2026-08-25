package com.propertyops.pms.adapter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdapterModePropertiesTest {
    @Test
    void rejectsUnimplementedProductionModesInsteadOfSilentlyConnecting() {
        AdapterModeProperties properties = new AdapterModeProperties();
        properties.setPayment("production");
        assertThatThrownBy(properties::validateFailClosedModes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");
    }
}
