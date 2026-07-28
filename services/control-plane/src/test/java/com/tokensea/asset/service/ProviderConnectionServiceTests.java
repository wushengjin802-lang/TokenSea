package com.tokensea.asset.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConnectionServiceTests {

    @Test
    void acceptsMoonshotOfficialApiBaseWhenHostIsConfigured() {
        ProviderConnectionService service = new ProviderConnectionService(
                null,
                null,
                "api.moonshot.cn",
                "80,443",
                "127.0.0.1",
                18080,
                false,
                "");

        ProviderConnectionService.Target target = service.target("https://api.moonshot.cn/v1");

        assertThat(target.host()).isEqualTo("api.moonshot.cn");
        assertThat(target.port()).isEqualTo(443);
        assertThat(target.uri().toString()).isEqualTo("https://api.moonshot.cn/v1/models");
    }
}
