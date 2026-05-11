package com.hotelvista.aiconcierge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync  // Bật @Async cho AuditService
public class AiConciergeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiConciergeApplication.class, args);
    }

}
