package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.service.AuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ManualPricingCreationIntegrationTests {
    @Test
    void manuallyCreatesProviderPriceCatalogAndPersistsNormalizedComponents() {
        TestDatabase database = database();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AuditService audits = mock(AuditService.class);
        PricingComponentService components = new PricingComponentService(json);
        ProviderPriceCatalogService matcher = new ProviderPriceCatalogService(database.jdbc(), json, audits, components);
        ProviderPriceCatalogController controller = new ProviderPriceCatalogController(
                database.jdbc(), json, audits, matcher, components);

        var response = controller.create(new ProviderPriceCatalogController.CatalogRequest(
                "deepseek", "deepseek-v4-pro", "DeepSeek V4 Pro", List.of("deepseek-v4-pro-latest"),
                "CNY", "TOKEN", 1_000_000L, new BigDecimal("2.00"), null, "NOT_APPLICABLE",
                null, "NOT_APPLICABLE", new BigDecimal("8.00"), List.of(), "MANUAL_VERIFIED",
                "https://manual.example/deepseek-v4-pro", new BigDecimal("0.95"), OffsetDateTime.now(),
                OffsetDateTime.now().minusMinutes(1), null, "ACTIVE"), null);

        String catalogId = String.valueOf(response.data().get("id"));
        Map<String,Object> catalog = database.jdbc().queryForMap("""
            select provider_type,provider_model_name,currency,input_unit_price,output_unit_price,
              cache_read_mode,cache_write_mode,price_completeness_status,status
            from provider_model_price_catalog where id=?
            """, catalogId);
        assertThat(catalog).containsEntry("provider_type", "deepseek")
                .containsEntry("provider_model_name", "deepseek-v4-pro")
                .containsEntry("currency", "CNY")
                .containsEntry("cache_read_mode", "NOT_APPLICABLE")
                .containsEntry("cache_write_mode", "NOT_APPLICABLE")
                .containsEntry("price_completeness_status", "UNSUPPORTED_CACHE")
                .containsEntry("status", "ACTIVE");
        assertThat((BigDecimal) catalog.get("input_unit_price")).isEqualByComparingTo("2.00");
        assertThat((BigDecimal) catalog.get("output_unit_price")).isEqualByComparingTo("8.00");
        assertThat(database.jdbc().queryForObject(
                "select count(*) from provider_price_component where catalog_price_id=?", Integer.class, catalogId))
                .isEqualTo(4);
    }

    @Test
    void manuallyCreatesPriceVersionAndPersistsComponentSummary() {
        TestDatabase database = database();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AuditService audits = mock(AuditService.class);
        PricingComponentService components = new PricingComponentService(json);
        GovernanceController controller = new GovernanceController(database.jdbc(), json, audits,
                mock(GovernanceApprovalService.class), components);
        database.jdbc().update("""
            insert into public_model_reference(id,canonical_name,display_name,source_type)
            values('reference-manual-it','deepseek/deepseek-v4-pro','DeepSeek V4 Pro','MANUAL')
            """);

        var response = controller.createPrice(new GovernanceController.PriceRequest(
                "PUBLIC_REFERENCE", "reference-manual-it", null, null, "CNY", "TOKEN", 1_000_000L,
                new BigDecimal("2.00"), new BigDecimal("0.20"), "EXPLICIT", null, "NOT_APPLICABLE",
                new BigDecimal("8.00"), List.of(), "MANUAL_VERIFIED", "manual://deepseek-v4-pro",
                new BigDecimal("1.00"), 3, OffsetDateTime.now().minusMinutes(1), null,
                null, null, null, null));

        String versionId = String.valueOf(response.data().get("id"));
        Map<String,Object> version = database.jdbc().queryForMap("""
            select price_layer,currency,input_unit_price,cache_read_unit_price,cache_read_mode,
              cache_write_mode,output_unit_price,component_schema_version,price_completeness_status,status
            from price_version where id=?
            """, versionId);
        assertThat(version).containsEntry("price_layer", "PUBLIC_REFERENCE")
                .containsEntry("currency", "CNY")
                .containsEntry("cache_read_mode", "EXPLICIT")
                .containsEntry("cache_write_mode", "NOT_APPLICABLE")
                .containsEntry("component_schema_version", 2)
                .containsEntry("price_completeness_status", "COMPLETE")
                .containsEntry("status", "DRAFT");
        assertThat((BigDecimal) version.get("cache_read_unit_price")).isEqualByComparingTo("0.20");
        assertThat(database.jdbc().queryForObject("""
                select jsonb_array_length(price_components) from price_version where id=?
                """, Integer.class, versionId)).isEqualTo(4);
    }

    private static TestDatabase database() {
        String url = System.getProperty("tokensea.it.db.url", "");
        Assumptions.assumeTrue(!url.isBlank(), "set -Dtokensea.it.db.url to run PostgreSQL integration test");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getProperty("tokensea.it.db.user", "postgres"),
                System.getProperty("tokensea.it.db.password", ""));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        return new TestDatabase(new JdbcTemplate(dataSource));
    }

    private record TestDatabase(JdbcTemplate jdbc) {}
}
