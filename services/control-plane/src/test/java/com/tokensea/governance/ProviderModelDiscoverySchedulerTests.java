package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderModelDiscoverySchedulerTests {
    @Test
    void createsSixHourManagedDiscoverySourceForEveryEnabledChannel() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("from provider_instance"))).thenReturn(List.of(Map.of(
                "id", "channel-1",
                "instance_name", "Kimi Production",
                "api_base", "https://api.moonshot.cn/v1",
                "provider_type", "moonshot",
                "region", "cn")));
        ProviderModelDiscoveryScheduler scheduler = new ProviderModelDiscoveryScheduler(
                jdbc, new ObjectMapper().findAndRegisterModules());

        scheduler.reconcileManagedSources();

        verify(jdbc).update(contains("insert into data_source"), any(Object[].class));
        verify(jdbc).update(contains("managedBy"));
    }
}
