package com.poc.adls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * ADLS Gen2 ↔ Azure App Service Bidirectional File Transfer PoC.
 *
 * Phase 2 — Spring Boot 3.3 + Spring MVC.
 * Authentication: System-Assigned Managed Identity — zero secrets.
 *
 * Endpoints:
 *   GET  /actuator/health        → liveness probe (Spring Actuator)
 *   GET  /read?file={fileName}   → read file from ADLS → ASP
 *   POST /write?file={fileName}  → ASP generates content internally → ADLS  ← F8 FIX
 */
@SpringBootApplication
public class AdlsApplication {

    private static final Logger LOG = LoggerFactory.getLogger(AdlsApplication.class);

    public static void main(final String[] args) {
        SpringApplication.run(AdlsApplication.class, args);
    }

    /**
     * Fires after Spring context is fully initialized and server is ready.
     * Confirms all beans wired and application is accepting requests.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        LOG.info("╔══════════════════════════════════════════════╗");
        LOG.info("║  ADLS ↔ ASP File Transfer PoC — READY  ✅   ║");
        LOG.info("╚══════════════════════════════════════════════╝");
        LOG.info("  GET  /actuator/health        → liveness probe");
        LOG.info("  GET  /read?file={{fileName}}   → ADLS → ASP");
        LOG.info("  POST /write?file={{fileName}}  → ASP generates content → ADLS");
    }
}
