package com.tokensea.governance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveCostPriceResolverTests {
    @Test
    void resolvesContractThenChannelThenOfficialWithoutUsingPublicReference() {
        JdbcTemplate jdbc = database();
        jdbc.update("""
            insert into provider_instance(id,instance_name,provider_type,region,status)
            values('channel-price','Kimi CN','moonshot','CN','启用')
            """);
        jdbc.update("""
            insert into provider_model_snapshot(id,provider_instance_id,source_endpoint,http_status,checksum,raw_payload)
            values('snapshot-price','channel-price','https://api.moonshot.cn/v1/models',200,repeat('a',64),'{}')
            """);
        jdbc.update("""
            insert into channel_model_deployment(id,provider_instance_id,provider_model_name,raw_model,source_snapshot_id)
            values('deployment-price','channel-price','kimi-test','{}','snapshot-price')
            """);
        insertPrice(jdbc, "official-price", "PROVIDER_OFFICIAL", "1", "2", null);
        insertPrice(jdbc, "channel-actual-price", "CHANNEL_ACTUAL", "0.8", "1.8", null);
        insertPrice(jdbc, "contract-price", "CONTRACT_PRICE", "0.6", "1.5", "contract-2026");

        EffectiveCostPriceResolver resolver = new EffectiveCostPriceResolver(jdbc);
        var contract = resolver.resolve("deployment-price", OffsetDateTime.now(),
                null, "STANDARD", "DEFAULT", "DEFAULT");
        assertThat(contract.priceVersionId()).isEqualTo("contract-price");
        assertThat(contract.priceLayer()).isEqualTo("CONTRACT_PRICE");
        assertThat(contract.region()).isEqualTo("cn");
        assertThat(contract.resolutionReason()).contains("合同价");
        assertThat(resolver.exists("deployment-price")).isTrue();

        jdbc.update("update price_version set status='RETIRED' where id='contract-price'");
        var channel = resolver.resolve("deployment-price", OffsetDateTime.now(),
                "cn", "STANDARD", "DEFAULT", "DEFAULT");
        assertThat(channel.priceVersionId()).isEqualTo("channel-actual-price");
        assertThat(channel.priceLayer()).isEqualTo("CHANNEL_ACTUAL");

        jdbc.update("update price_version set status='RETIRED' where id='channel-actual-price'");
        var official = resolver.resolve("deployment-price", OffsetDateTime.now(),
                "cn", "STANDARD", "DEFAULT", "DEFAULT");
        assertThat(official.priceVersionId()).isEqualTo("official-price");
        assertThat(official.priceLayer()).isEqualTo("PROVIDER_OFFICIAL");
        assertThat(official.cacheReadUnitPrice()).isEqualTo(new BigDecimal("0.020000000000"));
        assertThat(official.cacheReadMode()).isEqualTo("EXPLICIT");
        assertThat(official.cacheWriteUnitPrice()).isNull();
        assertThat(official.cacheWriteMode()).isEqualTo("NOT_APPLICABLE");
    }

    private static void insertPrice(JdbcTemplate jdbc, String id, String layer,
                                    String input, String output, String contractId) {
        jdbc.update("""
            insert into price_version(
              id,price_layer,deployment_id,currency,billing_basis,billing_quantity,
              input_unit_price,cache_read_unit_price,cache_read_mode,cache_write_unit_price,cache_write_mode,
              output_unit_price,price_components,component_schema_version,
              price_completeness_status,source_type,source_ref,version,effective_from,status,
              region,request_mode,service_tier,context_tier,contract_id)
            values(?,?, 'deployment-price','CNY','TOKEN',1000000,?,0.02,'EXPLICIT',null,'NOT_APPLICABLE',?,
              '[{"componentType":"INPUT_TOKEN","variant":"DEFAULT","unitPrice":1,"unitBasis":"TOKEN","unitQuantity":1000000,"mode":"EXPLICIT","priority":100,"scope":{},"sourceRef":"test","metadata":{}},{"componentType":"CACHE_READ_TOKEN","variant":"DEFAULT","unitPrice":0.02,"unitBasis":"TOKEN","unitQuantity":1000000,"mode":"EXPLICIT","priority":100,"scope":{},"sourceRef":"test","metadata":{}},{"componentType":"CACHE_WRITE_TOKEN","variant":"DEFAULT","unitPrice":null,"unitBasis":"TOKEN","unitQuantity":1000000,"mode":"NOT_APPLICABLE","priority":100,"scope":{},"sourceRef":"test","metadata":{}},{"componentType":"OUTPUT_TOKEN","variant":"DEFAULT","unitPrice":2,"unitBasis":"TOKEN","unitQuantity":1000000,"mode":"EXPLICIT","priority":100,"scope":{},"sourceRef":"test","metadata":{}}]',
              2,'COMPLETE','MANUAL_VERIFIED','test://price',1,now(),'ACTIVE','cn','STANDARD','DEFAULT','DEFAULT',?)
            """, id, layer, new BigDecimal(input), new BigDecimal(output), contractId);
    }

    private static JdbcTemplate database() {
        String url = System.getProperty("tokensea.it.db.url", "");
        Assumptions.assumeTrue(!url.isBlank(), "set -Dtokensea.it.db.url to run PostgreSQL integration test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getProperty("tokensea.it.db.user", "postgres"),
                System.getProperty("tokensea.it.db.password", ""));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        return new JdbcTemplate(dataSource);
    }
}
