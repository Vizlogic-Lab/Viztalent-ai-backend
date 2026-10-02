package com.smartstaff.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** F15: refreshes bank-question quality flags every hour, excluding newly
 *  flagged items from generation. Off in tests, which call the service. */
@Component
public class QuestionQualityScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuestionQualityScheduler.class);

    private final AnalyticsService analytics;
    private final boolean enabled;

    public QuestionQualityScheduler(AnalyticsService analytics,
                                    @Value("${app.analytics.quality-refresh-enabled:true}") boolean enabled) {
        this.analytics = analytics;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.analytics.quality-refresh-interval-ms:3600000}",
               initialDelayString = "${app.analytics.quality-refresh-interval-ms:3600000}")
    public void refresh() {
        if (!enabled) return;
        try {
            Map<String, Object> r = analytics.refreshBankQuality();
            List<?> excluded = (List<?>) r.get("newly_excluded");
            if (!excluded.isEmpty()) log.info("Question quality: excluded {} bank question(s) from generation", excluded.size());
        } catch (Exception e) {
            log.error("Question quality refresh failed", e);
        }
    }
}
