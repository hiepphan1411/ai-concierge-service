package com.hotelvista.aiconcierge.config;

import com.hotelvista.aiconcierge.service.ApiKeyManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ApiKeyInitializer implements ApplicationRunner {

    @Autowired
    private ApiKeyManagementService apiKeyManagementService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Initializing API Keys");
        try {
            apiKeyManagementService.initializeApiKeys();
            log.info("API Keys initialized successfully");
        } catch (Exception e) {
            log.error("Error initializing API keys: {}", e.getMessage(), e);
        }
    }
}
