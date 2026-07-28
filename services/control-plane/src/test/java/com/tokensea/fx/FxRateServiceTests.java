package com.tokensea.fx;

import com.tokensea.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FxRateServiceTests {
    @Test
    void parsesOfficialEcbXmlAndCalculatesUsdToCnyCrossRate() throws Exception {
        FxRateService service = new FxRateService(mock(JdbcTemplate.class), mock(AuditService.class),
                mock(PlatformTransactionManager.class),
                "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml", "USD,EUR", "", 18080);
        byte[] xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                  xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                  <Cube><Cube time="2026-07-21">
                    <Cube currency="USD" rate="1.1800"/>
                    <Cube currency="CNY" rate="8.4960"/>
                  </Cube></Cube>
                </gesmes:Envelope>
                """.getBytes(StandardCharsets.UTF_8);

        FxRateService.EcbSnapshot snapshot = service.parse(xml);

        assertThat(snapshot.sourceDate()).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(service.crossRate(snapshot, "USD", "CNY")).isEqualByComparingTo(new BigDecimal("7.200000000000"));
        assertThat(service.crossRate(snapshot, "EUR", "CNY")).isEqualByComparingTo(new BigDecimal("8.496000000000"));
    }
}
