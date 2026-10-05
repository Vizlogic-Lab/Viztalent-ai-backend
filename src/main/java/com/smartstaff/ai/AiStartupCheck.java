package com.smartstaff.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** In prod, tests the AI connection once after startup and logs a clear WARN
 *  (never the key) when it fails, so a retired model or an exhausted key shows
 *  up in the logs before a candidate hits it. */
@Component
@Profile("prod")
public class AiStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(AiStartupCheck.class);

    private final AiConnectionService connection;

    public AiStartupCheck(AiConnectionService connection) {
        this.connection = connection;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        try {
            AiConfigService.ConnectionTest t = connection.test();
            if (t.ok()) {
                log.info("AI connection OK: model '{}' answered in {} ms", t.model(), t.latency_ms());
            } else {
                log.warn("AI connection check FAILED at startup [{}]: {} (model '{}', fallback '{}'). "
                                + "Fix it in Settings → AI provider; AI features will fail or fall back until then.",
                        t.error_code(), t.message(), t.model(), t.fallback_model());
            }
        } catch (Exception e) {
            log.warn("AI connection check could not run at startup: {}", e.getClass().getSimpleName());
        }
    }
}
