package com.tokensea.governance;

import com.tokensea.common.OperationException;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelDiscoveryAutoProbeServiceTests {
    @Test
    void runsChatProbeForQueuedDeployment() {
        CapabilityProbeService probes = mock(CapabilityProbeService.class);

        new ModelDiscoveryAutoProbeService(probes).probeChat("deployment-1");

        verify(probes).probe("deployment-1", "CHAT", null);
    }

    @Test
    void keepsProbeFailureInBackground() {
        CapabilityProbeService probes = mock(CapabilityProbeService.class);
        doThrow(OperationException.conflict("PROBE_FAILED", "test", "failed", "retry"))
                .when(probes).probe("deployment-1", "CHAT", null);

        new ModelDiscoveryAutoProbeService(probes).probeChat("deployment-1");

        verify(probes).probe("deployment-1", "CHAT", null);
    }
}
