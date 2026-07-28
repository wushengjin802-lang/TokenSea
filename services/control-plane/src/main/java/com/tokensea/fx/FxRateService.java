package com.tokensea.fx;

import com.tokensea.audit.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class FxRateService {
    public static final String BASE_CURRENCY = "CNY";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RESPONSE_BYTES = 1_000_000;

    private final JdbcTemplate jdbc;
    private final AuditService audits;
    private final TransactionTemplate transactions;
    private final HttpClient http;
    private final String defaultSourceUrl;
    private final Set<String> defaultManagedCurrencies;

    public FxRateService(JdbcTemplate jdbc,
                         AuditService audits,
                         PlatformTransactionManager transactionManager,
                         @Value("${tokensea.fx.source-url:https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml}") String defaultSourceUrl,
                         @Value("${tokensea.fx.managed-currencies:USD}") String managedCurrencies,
                         @Value("${tokensea.egress.proxy-host:}") String proxyHost,
                         @Value("${tokensea.egress.proxy-port:18080}") int proxyPort) {
        this.jdbc = jdbc;
        this.audits = audits;
        this.transactions = new TransactionTemplate(transactionManager);
        this.defaultSourceUrl = defaultSourceUrl;
        this.defaultManagedCurrencies = parseCurrencies(managedCurrencies);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxyHost != null && !proxyHost.isBlank()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.http = builder.build();
    }

    public record ManualRateRequest(LocalDate rateMonth, String fromCurrency, String toCurrency,
                                    BigDecimal rate, String note) {}
    public record SyncSummary(String runId, String status, LocalDate rateMonth, LocalDate sourceDate,
                              int recordsWritten, int recordsSkipped, String message) {}
    record EcbSnapshot(LocalDate sourceDate, Map<String, BigDecimal> perEuro) {}

    @Scheduled(cron = "${tokensea.fx.monthly-cron:0 10 3 1 * *}", zone = "${tokensea.fx.zone:Asia/Shanghai}")
    public void monthlySync() {
        if (autoUpdateEnabled()) sync(monthStart(LocalDate.now(BUSINESS_ZONE)), "SCHEDULED", "SYSTEM", true, false);
    }

    @Scheduled(cron = "${tokensea.fx.recovery-cron:0 40 3 * * *}", zone = "${tokensea.fx.zone:Asia/Shanghai}")
    public void recoverMissingCurrentMonthRates() {
        if (!autoUpdateEnabled()) return;
        LocalDate month = monthStart(LocalDate.now(BUSINESS_ZONE));
        if (missingManagedCurrencies(month).isEmpty()) return;
        sync(month, "RECOVERY", "SYSTEM", false, false);
    }

    public List<Map<String,Object>> list(LocalDate rateMonth, String fromCurrency, String status) {
        return jdbc.queryForList("""
            select r.*,
              case when r.status='ACTIVE' then true else false end current_version,
              s.status sync_status,s.trigger_type,s.completed_at sync_completed_at
            from fx_rate r
            left join fx_rate_sync_run s on s.id=r.sync_run_id
            where (?::date is null or r.rate_month=?)
              and (?::text is null or r.from_currency=upper(?))
              and (?::text is null or r.status=upper(?))
            order by r.rate_month desc,r.from_currency,r.version desc
            """, rateMonth, rateMonth, blankToNull(fromCurrency), blankToNull(fromCurrency),
                blankToNull(status), blankToNull(status));
    }

    public Map<String,Object> status() {
        LocalDate currentMonth = monthStart(LocalDate.now(BUSINESS_ZONE));
        List<Map<String,Object>> active = jdbc.queryForList("""
            select * from fx_rate where rate_month=? and status='ACTIVE' order by from_currency
            """, currentMonth);
        List<Map<String,Object>> runs = jdbc.queryForList("""
            select * from fx_rate_sync_run order by started_at desc limit 1
            """);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("baseCurrency", BASE_CURRENCY);
        result.put("currentMonth", currentMonth);
        result.put("autoUpdateEnabled", autoUpdateEnabled());
        result.put("managedCurrencies", managedCurrencies());
        result.put("activeRates", active);
        result.put("missingCurrencies", missingManagedCurrencies(currentMonth));
        result.put("lastSync", runs.isEmpty() ? null : runs.getFirst());
        result.put("sourceUrl", sourceUrl());
        result.put("schedule", "每月1日 03:10（北京时间），缺失时每日03:40重试");
        return result;
    }

    public SyncSummary syncNow(String actor) {
        return sync(monthStart(LocalDate.now(BUSINESS_ZONE)), "MANUAL_TRIGGER", actor, true, false);
    }

    public Map<String,Object> saveManual(ManualRateRequest request, String actor) {
        validateManual(request);
        LocalDate month = monthStart(request.rateMonth());
        String from = request.fromCurrency().trim().toUpperCase(Locale.ROOT);
        String to = request.toCurrency().trim().toUpperCase(Locale.ROOT);
        Map<String,Object> before = activeRate(month, from, to);
        Map<String,Object> after = transactions.execute(status -> {
            lock(month, from, to);
            int version = nextVersion(month, from, to);
            jdbc.update("""
                update fx_rate set status='SUPERSEDED',updated_at=now()
                where rate_month=? and from_currency=? and to_currency=? and status='ACTIVE'
                """, month, from, to);
            String id = id();
            jdbc.update("""
                insert into fx_rate(id,rate_month,from_currency,to_currency,rate,source_type,source_ref,
                  source_date,note,status,version,created_by)
                values(?,?,?,?,?,'MANUAL','manual://fx-rate',?,?, 'ACTIVE',?,?)
                """, id, month, from, to, request.rate(), LocalDate.now(BUSINESS_ZONE), request.note().trim(), version, actor);
            return jdbc.queryForMap("select * from fx_rate where id=?", id);
        });
        audits.record("FX_RATE_MANUAL_OVERRIDE", "FxRate", String.valueOf(after.get("id")), before, after);
        return after;
    }

    public SyncSummary restoreAutomatic(String rateId, String actor) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from fx_rate where id=?", rateId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "汇率记录不存在");
        Map<String,Object> current = rows.getFirst();
        LocalDate month = date(current.get("rate_month"));
        String from = String.valueOf(current.get("from_currency"));
        String to = String.valueOf(current.get("to_currency"));
        List<Map<String,Object>> automaticRows = jdbc.queryForList("""
            select * from fx_rate
            where rate_month=? and from_currency=? and to_currency=? and source_type='AUTOMATIC_ECB'
              and id<>?
            order by version desc limit 1
            """, month, from, to, rateId);
        if (automaticRows.isEmpty()) {
            if (!month.equals(monthStart(LocalDate.now(BUSINESS_ZONE)))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该历史月份没有可恢复的自动汇率版本，请人工维护该月份汇率");
            }
            return sync(month, "RESTORE_AUTO", actor, true, true, Set.of(from));
        }
        Map<String,Object> automatic = automaticRows.getFirst();
        String runId = id();
        jdbc.update("""
            insert into fx_rate_sync_run(id,trigger_type,rate_month,source_url,status,source_date,created_by)
            values(?, 'RESTORE_AUTO', ?, ?, 'RUNNING', ?, ?)
            """, runId, month, automatic.get("source_ref"), automatic.get("source_date"), actor);
        Map<String,Object> restored;
        try {
            restored = transactions.execute(status -> {
                lock(month, from, to);
                int version = nextVersion(month, from, to);
                jdbc.update("""
                    update fx_rate set status='SUPERSEDED',updated_at=now()
                    where rate_month=? and from_currency=? and to_currency=? and status='ACTIVE'
                    """, month, from, to);
                String id = id();
                jdbc.update("""
                    insert into fx_rate(id,rate_month,from_currency,to_currency,rate,source_type,source_ref,
                      source_date,note,status,version,sync_run_id,created_by)
                    values(?,?,?,?,?,'AUTOMATIC_ECB',?,?,?,'ACTIVE',?,?,?)
                    """, id, month, from, to, automatic.get("rate"), automatic.get("source_ref"),
                        automatic.get("source_date"), "恢复历史 ECB 自动汇率版本 V" + automatic.get("version"),
                        version, runId, actor);
                return jdbc.queryForMap("select * from fx_rate where id=?", id);
            });
            jdbc.update("""
                update fx_rate_sync_run set status='SUCCEEDED',records_written=1,records_skipped=0,completed_at=now()
                where id=?
                """, runId);
        } catch (Exception e) {
            String message = rootMessage(e);
            jdbc.update("""
                update fx_rate_sync_run set status='FAILED',error_message=?,completed_at=now() where id=?
                """, message, runId);
            throw e;
        }
        try {
            audits.record("FX_RATE_RESTORE_AUTO", "FxRate", String.valueOf(restored.get("id")), current, restored);
        } catch (Exception auditError) {
            String message = "汇率已恢复，但审计记录失败：" + rootMessage(auditError);
            jdbc.update("update fx_rate_sync_run set status='PARTIAL',error_message=? where id=?", message, runId);
            return new SyncSummary(runId, "PARTIAL", month, date(automatic.get("source_date")), 1, 0, message);
        }
        return new SyncSummary(runId, "SUCCEEDED", month, date(automatic.get("source_date")), 1, 0,
                "已恢复历史 ECB 自动汇率");
    }

    public Map<String,Object> setAutoUpdate(boolean enabled, String actor) {
        Map<String,Object> before = settingRow("FX_AUTO_UPDATE_ENABLED");
        jdbc.update("""
            insert into platform_setting(setting_key,setting_value,description,sensitive,updated_at)
            values('FX_AUTO_UPDATE_ENABLED',?,'每月自动更新汇率',false,now())
            on conflict(setting_key) do update set setting_value=excluded.setting_value,updated_at=now()
            """, Boolean.toString(enabled));
        Map<String,Object> after = settingRow("FX_AUTO_UPDATE_ENABLED");
        audits.record("FX_AUTO_UPDATE_CHANGE", "PlatformSetting", "FX_AUTO_UPDATE_ENABLED", before,
                Map.of("value", after, "actor", actor));
        return Map.of("enabled", enabled, "updatedAt", after.get("updated_at"));
    }

    private SyncSummary sync(LocalDate month, String trigger, String actor, boolean forceAutomatic,
                             boolean overrideManual) {
        return sync(month, trigger, actor, forceAutomatic, overrideManual, managedCurrencies());
    }

    private SyncSummary sync(LocalDate month, String trigger, String actor, boolean forceAutomatic,
                             boolean overrideManual, Set<String> requestedCurrencies) {
        String sourceUrl = sourceUrl();
        String runId = id();
        jdbc.update("""
            insert into fx_rate_sync_run(id,trigger_type,rate_month,source_url,status,created_by)
            values(?,?,?,?, 'RUNNING',?)
            """, runId, trigger, month, sourceUrl, actor);
        try {
            EcbSnapshot snapshot = fetch(sourceUrl);
            int[] result = transactions.execute(status -> {
                int written = 0;
                int skipped = 0;
                for (String currency : requestedCurrencies) {
                    String from = currency.toUpperCase(Locale.ROOT);
                    if (BASE_CURRENCY.equals(from)) continue;
                    lock(month, from, BASE_CURRENCY);
                    Map<String,Object> active = activeRate(month, from, BASE_CURRENCY);
                    if (active != null && "MANUAL".equals(active.get("source_type")) && !overrideManual) {
                        skipped++;
                        continue;
                    }
                    BigDecimal rate = crossRate(snapshot, from, BASE_CURRENCY);
                    if (active != null && "AUTOMATIC_ECB".equals(active.get("source_type"))) {
                        BigDecimal activeRate = new BigDecimal(String.valueOf(active.get("rate")));
                        boolean unchanged = activeRate.compareTo(rate) == 0
                                && Objects.equals(date(active.get("source_date")), snapshot.sourceDate());
                        if (!forceAutomatic || unchanged) {
                            skipped++;
                            continue;
                        }
                    }
                    int version = nextVersion(month, from, BASE_CURRENCY);
                    jdbc.update("""
                        update fx_rate set status='SUPERSEDED',updated_at=now()
                        where rate_month=? and from_currency=? and to_currency=? and status='ACTIVE'
                        """, month, from, BASE_CURRENCY);
                    jdbc.update("""
                        insert into fx_rate(id,rate_month,from_currency,to_currency,rate,source_type,source_ref,
                          source_date,note,status,version,sync_run_id,created_by)
                        values(?,?,?,?,?,'AUTOMATIC_ECB',?,?,?,'ACTIVE',?,?,?)
                        """, id(), month, from, BASE_CURRENCY, rate, sourceUrl, snapshot.sourceDate(),
                            "ECB 欧元参考汇率交叉换算", version, runId, actor);
                    written++;
                }
                return new int[]{written, skipped};
            });
            String state = "SUCCEEDED";
            jdbc.update("""
                update fx_rate_sync_run set status=?,source_date=?,records_written=?,records_skipped=?,completed_at=now()
                where id=?
                """, state, snapshot.sourceDate(), result[0], result[1], runId);
            Map<String,Object> after = jdbc.queryForMap("select * from fx_rate_sync_run where id=?", runId);
            try {
                audits.record("FX_RATE_SYNC", "FxRateSyncRun", runId, null, after);
            } catch (Exception auditError) {
                String auditMessage = "汇率已写入，但审计记录失败：" + rootMessage(auditError);
                jdbc.update("""
                    update fx_rate_sync_run set status='PARTIAL',error_message=? where id=?
                    """, auditMessage, runId);
                return new SyncSummary(runId, "PARTIAL", month, snapshot.sourceDate(), result[0], result[1], auditMessage);
            }
            return new SyncSummary(runId, state, month, snapshot.sourceDate(), result[0], result[1],
                    result[0] == 0 ? "没有写入新汇率；可能已存在人工覆盖或当月自动版本" : "汇率同步完成");
        } catch (Exception e) {
            String message = rootMessage(e);
            jdbc.update("""
                update fx_rate_sync_run set status='FAILED',error_message=?,completed_at=now() where id=?
                """, message, runId);
            return new SyncSummary(runId, "FAILED", month, null, 0, 0, message);
        }
    }

    private EcbSnapshot fetch(String sourceUrl) throws Exception {
        URI uri = URI.create(sourceUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !(uri.getHost().equalsIgnoreCase("ecb.europa.eu")
                || uri.getHost().toLowerCase(Locale.ROOT).endsWith(".ecb.europa.eu"))) {
            throw new IllegalArgumentException("汇率自动来源必须是 ECB 官方 HTTPS 地址");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/xml,text/xml;q=0.9,*/*;q=0.1")
                .header("User-Agent", "TokenSea-FxRateSync/1.0")
                .GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("ECB 汇率源返回 HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("ECB 汇率响应为空或超过大小限制");
        }
        EcbSnapshot snapshot = parse(body);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (snapshot.sourceDate().isAfter(today) || snapshot.sourceDate().isBefore(today.minusDays(10))) {
            throw new IllegalStateException("ECB 汇率来源日期异常或过期：" + snapshot.sourceDate());
        }
        return snapshot;
    }

    EcbSnapshot parse(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        NodeList cubes = document.getElementsByTagNameNS("*", "Cube");
        LocalDate sourceDate = null;
        Map<String,BigDecimal> perEuro = new LinkedHashMap<>();
        perEuro.put("EUR", BigDecimal.ONE);
        for (int i = 0; i < cubes.getLength(); i++) {
            if (!(cubes.item(i) instanceof Element element)) continue;
            String time = element.getAttribute("time");
            if (!time.isBlank()) sourceDate = LocalDate.parse(time);
            String currency = element.getAttribute("currency");
            String rate = element.getAttribute("rate");
            if (!currency.isBlank() && !rate.isBlank()) {
                perEuro.put(currency.toUpperCase(Locale.ROOT), new BigDecimal(rate));
            }
        }
        if (sourceDate == null || !perEuro.containsKey("CNY") || !perEuro.containsKey("USD")) {
            throw new IllegalStateException("ECB 汇率响应缺少日期、CNY 或 USD 数据");
        }
        return new EcbSnapshot(sourceDate, perEuro);
    }

    BigDecimal crossRate(EcbSnapshot snapshot, String from, String to) {
        BigDecimal fromPerEuro = snapshot.perEuro().get(from);
        BigDecimal toPerEuro = snapshot.perEuro().get(to);
        if (fromPerEuro == null || toPerEuro == null) {
            throw new IllegalStateException("ECB 汇率源不包含币种 " + from + " 或 " + to);
        }
        return toPerEuro.divide(fromPerEuro, 12, RoundingMode.HALF_UP);
    }

    private Set<String> missingManagedCurrencies(LocalDate month) {
        Set<String> missing = new LinkedHashSet<>();
        for (String currency : managedCurrencies()) {
            if (BASE_CURRENCY.equals(currency)) continue;
            Integer count = jdbc.queryForObject("""
                select count(*) from fx_rate where rate_month=? and from_currency=? and to_currency=? and status='ACTIVE'
                """, Integer.class, month, currency, BASE_CURRENCY);
            if (count == null || count == 0) missing.add(currency);
        }
        return missing;
    }

    private Set<String> managedCurrencies() {
        String configured = setting("FX_MANAGED_CURRENCIES", String.join(",", defaultManagedCurrencies));
        return parseCurrencies(configured);
    }

    private boolean autoUpdateEnabled() {
        return Boolean.parseBoolean(setting("FX_AUTO_UPDATE_ENABLED", "true"));
    }

    private String sourceUrl() {
        return setting("FX_RATE_SOURCE_URL", defaultSourceUrl);
    }

    private String setting(String key, String fallback) {
        List<String> values = jdbc.queryForList("select setting_value from platform_setting where setting_key=?",
                String.class, key);
        return values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank() ? fallback : values.getFirst();
    }

    private Map<String,Object> settingRow(String key) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from platform_setting where setting_key=?", key);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private Map<String,Object> activeRate(LocalDate month, String from, String to) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select * from fx_rate where rate_month=? and from_currency=? and to_currency=? and status='ACTIVE'
            order by version desc limit 1
            """, month, from, to);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private int nextVersion(LocalDate month, String from, String to) {
        Integer version = jdbc.queryForObject("""
            select coalesce(max(version),0)+1 from fx_rate where rate_month=? and from_currency=? and to_currency=?
            """, Integer.class, month, from, to);
        return version == null ? 1 : version;
    }

    private void lock(LocalDate month, String from, String to) {
        Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtext(?))", Boolean.class,
                "fx:" + month + ":" + from + ":" + to);
        if (!Boolean.TRUE.equals(locked)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该币种月份的汇率正在更新，请稍后重试");
        }
    }

    private void validateManual(ManualRateRequest request) {
        if (request == null || request.rateMonth() == null || request.fromCurrency() == null
                || request.toCurrency() == null || request.rate() == null || request.rate().signum() <= 0
                || request.note() == null || request.note().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人工汇率必须填写月份、币种、正数汇率和修改原因");
        }
        String from = request.fromCurrency().trim().toUpperCase(Locale.ROOT);
        String to = request.toCurrency().trim().toUpperCase(Locale.ROOT);
        if (!from.matches("[A-Z]{3}") || !BASE_CURRENCY.equals(to) || from.equals(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人工汇率仅支持原币种折算到 CNY");
        }
        if (!request.rateMonth().equals(monthStart(request.rateMonth()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "汇率月份必须是当月第一天");
        }
    }

    private static Set<String> parseCurrencies(String raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw.split(",")) {
                String currency = value.trim().toUpperCase(Locale.ROOT);
                if (currency.matches("[A-Z]{3}")) values.add(currency);
            }
        }
        if (values.isEmpty()) values.add("USD");
        return values;
    }

    private static LocalDate monthStart(LocalDate value) {
        return value.withDayOfMonth(1);
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        if (value == null) return null;
        return LocalDate.parse(String.valueOf(value));
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
        return message.length() > 1900 ? message.substring(0, 1900) : message;
    }
}
