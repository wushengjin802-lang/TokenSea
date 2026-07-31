package com.tokensea.governance.pricing.reference;

import com.tokensea.governance.ProviderPriceSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ReferencePriceBootstrapService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReferencePriceBootstrapService.class);

    private final BuiltInReferenceSourceReconciler sources;
    private final ReferencePriceBundleLoader bundle;
    private final ProviderPriceSyncService sync;
    private final boolean enabled;
    private final boolean bootstrapEnabled;
    private final boolean immediateSync;

    public ReferencePriceBootstrapService(BuiltInReferenceSourceReconciler sources,
                                          ReferencePriceBundleLoader bundle,
                                          ProviderPriceSyncService sync,
                                          @Value("${tokensea.reference-price.enabled:true}") boolean enabled,
                                          @Value("${tokensea.reference-price.bootstrap-enabled:true}") boolean bootstrapEnabled,
                                          @Value("${tokensea.reference-price.immediate-sync-on-startup:true}") boolean immediateSync) {
        this.sources = sources;
        this.bundle = bundle;
        this.sync = sync;
        this.enabled = enabled;
        this.bootstrapEnabled = bootstrapEnabled;
        this.immediateSync = immediateSync;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!enabled) {
            try {
                int paused = sources.pauseAll();
                LOGGER.info("reference_price_bootstrap_disabled paused_sources={}", paused);
            } catch (Exception exception) {
                LOGGER.warn("reference_price_disable_failed error={}", exception.getMessage());
            }
            return;
        }
        try {
            int reconciled = sources.reconcile();
            ReferencePriceBundleLoader.BundleLoadResult loaded = bootstrapEnabled
                    ? bundle.load()
                    : new ReferencePriceBundleLoader.BundleLoadResult("DISABLED", "", 0, 0);
            int enqueued = 0;
            if (immediateSync) {
                for (String sourceId : sources.onlineSourceIds()) {
                    sync.enqueueScheduledNow(sourceId);
                    enqueued++;
                }
            }
            LOGGER.info("reference_price_bootstrap_completed sources={} bundle_status={} bundle_records={} enqueued={}",
                    reconciled, loaded.status(), loaded.records(), enqueued);
        } catch (Exception exception) {
            // Reference prices are non-authoritative. Startup and Gateway traffic must not fail when bootstrap fails.
            LOGGER.warn("reference_price_bootstrap_failed error={}", exception.getMessage());
        }
    }
}
