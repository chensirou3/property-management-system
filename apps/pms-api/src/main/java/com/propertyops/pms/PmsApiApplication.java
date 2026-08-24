package com.propertyops.pms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PmsApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(PmsApiApplication.class, args);
    }
}

