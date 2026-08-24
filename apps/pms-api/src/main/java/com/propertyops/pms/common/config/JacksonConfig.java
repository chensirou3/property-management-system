package com.propertyops.pms.common.config;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    @Bean
    Module decimalAsStringModule() {
        SimpleModule module = new SimpleModule("decimal-as-string");
        module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        return module;
    }
}
