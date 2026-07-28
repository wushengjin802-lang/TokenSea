package com.tokensea.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.mock;

class ProviderPriceApprovalLiveIntegrationTests {
    @Test
    void approvesExistingPendingDiffInsideRollbackOnlyTransaction() {
        String url = System.getenv("TOKENSEA_IT_DB_URL");
        String user = System.getenv("TOKENSEA_IT_DB_USER");
        String password = System.getenv("TOKENSEA_IT_DB_PASSWORD");
        String diffId = System.getProperty("tokensea.it.diff.id", "");
        String actorId = System.getProperty("tokensea.it.actor.id", "SYSTEM");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "TOKENSEA_IT_DB_URL is required");
        Assumptions.assumeTrue(user != null && !user.isBlank(), "TOKENSEA_IT_DB_USER is required");
        Assumptions.assumeTrue(password != null && !password.isBlank(), "TOKENSEA_IT_DB_PASSWORD is required");
        Assumptions.assumeTrue(!diffId.isBlank(), "tokensea.it.diff.id is required");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AuditService audits = mock(AuditService.class);
        ProviderConnectionService providerConnections = mock(ProviderConnectionService.class);
        PricingComponentService pricingComponents = new PricingComponentService(json);
        ProviderPriceCatalogService matcher = new ProviderPriceCatalogService(jdbc, json, audits, pricingComponents);
        ProviderPriceSyncService service = new ProviderPriceSyncService(
                jdbc,
                json,
                new PriceSourceParser(json),
                matcher,
                audits,
                providerConnections,
                pricingComponents,
                transactionManager,
                "",
                18080,
                "");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            service.approveDiff(diffId, actorId, "live approval diagnostic");
            status.setRollbackOnly();
        });
    }
}
