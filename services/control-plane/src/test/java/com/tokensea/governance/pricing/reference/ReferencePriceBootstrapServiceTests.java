package com.tokensea.governance.pricing.reference;

import com.tokensea.governance.ProviderPriceSyncService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class ReferencePriceBootstrapServiceTests {
    @Test
    void startupReconcilesLoadsBundleAndQueuesOnlineSources() {
        BuiltInReferenceSourceReconciler sources = mock(BuiltInReferenceSourceReconciler.class);
        ReferencePriceBundleLoader bundle = mock(ReferencePriceBundleLoader.class);
        ProviderPriceSyncService sync = mock(ProviderPriceSyncService.class);
        when(sources.reconcile()).thenReturn(3);
        when(sources.onlineSourceIds()).thenReturn(List.of("builtin_litellm_cost_map", "builtin_models_dev"));
        when(bundle.load()).thenReturn(new ReferencePriceBundleLoader.BundleLoadResult("LOADED", "2026.07.29.1", 2, 2));
        ReferencePriceBootstrapService service = new ReferencePriceBootstrapService(
                sources, bundle, sync, true, true, true);

        service.initialize();

        verify(sources).reconcile();
        verify(bundle).load();
        verify(sync).enqueueScheduledNow("builtin_litellm_cost_map");
        verify(sync).enqueueScheduledNow("builtin_models_dev");
    }

    @Test
    void disabledFeaturePausesSystemSourcesWithoutLoadingOrSyncing() {
        BuiltInReferenceSourceReconciler sources = mock(BuiltInReferenceSourceReconciler.class);
        ReferencePriceBundleLoader bundle = mock(ReferencePriceBundleLoader.class);
        ProviderPriceSyncService sync = mock(ProviderPriceSyncService.class);
        when(sources.pauseAll()).thenReturn(3);
        ReferencePriceBootstrapService service = new ReferencePriceBootstrapService(
                sources, bundle, sync, false, true, true);

        service.initialize();

        verify(sources).pauseAll();
        verifyNoInteractions(bundle, sync);
    }

    @Test
    void bootstrapFailureNeverBreaksApplicationStartup() {
        BuiltInReferenceSourceReconciler sources = mock(BuiltInReferenceSourceReconciler.class);
        ReferencePriceBundleLoader bundle = mock(ReferencePriceBundleLoader.class);
        ProviderPriceSyncService sync = mock(ProviderPriceSyncService.class);
        when(sources.reconcile()).thenThrow(new IllegalStateException("database unavailable"));
        ReferencePriceBootstrapService service = new ReferencePriceBootstrapService(
                sources, bundle, sync, true, true, true);

        assertDoesNotThrow(service::initialize);
        verifyNoInteractions(bundle, sync);
    }
}
