package com.tokensea.audit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuditServiceTests {
    @Test
    void normalizesLoopbackAndForwardedAddresses() {
        assertEquals("127.0.0.1", AuditService.normalizeIp("0:0:0:0:0:0:0:1"));
        assertEquals("127.0.0.1", AuditService.normalizeIp("::1"));
        assertEquals("10.1.2.3", AuditService.normalizeIp("10.1.2.3, 10.2.3.4"));
        assertEquals("192.168.1.8", AuditService.normalizeIp("::ffff:192.168.1.8"));
        assertNull(AuditService.normalizeIp("  "));
    }
}
