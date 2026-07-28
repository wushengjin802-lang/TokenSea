package com.tokensea.governance;

import com.tokensea.common.OperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ModelDiscoveryAutoProbeService {
    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryAutoProbeService.class);
    private final CapabilityProbeService probes;

    public ModelDiscoveryAutoProbeService(CapabilityProbeService probes) {
        this.probes = probes;
    }

    @Async("modelProbeExecutor")
    public void probeChat(String deploymentId) {
        try {
            probes.probe(deploymentId, "CHAT", null);
        } catch (OperationException exception) {
            log.info("模型自动探测未通过，deploymentId={}，errorCode={}", deploymentId, exception.code());
        } catch (Exception exception) {
            log.warn("模型自动探测执行异常，deploymentId={}", deploymentId, exception);
        }
    }
}
