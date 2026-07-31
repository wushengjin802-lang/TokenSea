package com.tokensea.governance.pricing.reference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReferencePriceBindingService {
    private static final Logger log = LoggerFactory.getLogger(ReferencePriceBindingService.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public ReferencePriceBindingService(
            JdbcTemplate jdbc,
            @Value("${tokensea.reference-price.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public int reconcileAll() {
        if (!enabled) return 0;
        Integer count = jdbc.queryForObject(
                "select tokensea_refresh_reference_price_bindings()",
                Integer.class);
        return count == null ? 0 : count;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAfterStartup() {
        safelyReconcile("startup");
    }

    @Scheduled(
            initialDelayString = "${tokensea.reference-price.binding-refresh-initial-delay-ms:30000}",
            fixedDelayString = "${tokensea.reference-price.binding-refresh-ms:60000}")
    public void reconcileScheduled() {
        safelyReconcile("scheduled");
    }

    private void safelyReconcile(String trigger) {
        if (!enabled) return;
        try {
            int activeBindings = reconcileAll();
            log.debug("reference price bindings refreshed trigger={} activeBindings={}", trigger, activeBindings);
        } catch (RuntimeException exception) {
            log.warn("reference price binding refresh failed trigger={}", trigger, exception);
        }
    }
}
