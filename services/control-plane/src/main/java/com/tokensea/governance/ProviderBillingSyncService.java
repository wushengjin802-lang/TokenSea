package com.tokensea.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokensea.asset.entity.ProviderInstance;
import com.tokensea.asset.service.ProviderConnectionService;
import com.tokensea.audit.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProviderBillingSyncService {
    private static final int MAX_RESPONSE_BYTES = 5_000_000;
    private static final int MAX_PAGES = 30;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProviderBillingParser parser;
    private final ProviderConnectionService connections;
    private final AuditService audits;
    private final HttpClient http;
    private final Set<String> globalAllowedHosts;
    private final boolean proxyConfigured;

    public ProviderBillingSyncService(JdbcTemplate jdbc,
                                      ObjectMapper json,
                                      ProviderBillingParser parser,
                                      ProviderConnectionService connections,
                                      AuditService audits,
                                      @Value("${tokensea.egress.proxy-host:}") String proxyHost,
                                      @Value("${tokensea.egress.proxy-port:18080}") int proxyPort,
                                      @Value("${tokensea.egress.allowed-hosts:}") String allowedHosts) {
        this.jdbc = jdbc;
        this.json = json;
        this.parser = parser;
        this.connections = connections;
        this.audits = audits;
        this.globalAllowedHosts = parseHosts(allowedHosts);
        this.proxyConfigured = proxyHost != null && !proxyHost.isBlank();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxyConfigured) builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        this.http = builder.build();
    }

    public record BillingPreview(int httpStatus,
                                 int records,
                                 BigDecimal amount,
                                 String currency,
                                 int pages,
                                 OffsetDateTime periodStart,
                                 OffsetDateTime periodEnd,
                                 List<Map<String,Object>> sample) {}

    public record BillingSyncSummary(String runId,
                                     String status,
                                     int records,
                                     BigDecimal amount,
                                     String currency,
                                     String reconciliationId) {}

    public BillingPreview test(String sourceId, OffsetDateTime from, OffsetDateTime to) {
        Map<String,Object> source = requireSource(sourceId);
        Period period = period(source, from, to);
        try {
            FetchResult fetched = fetch(source, period);
            List<ProviderBillingParser.BillingRecord> records = parse(source, fetched.content());
            BigDecimal amount = records.stream().map(ProviderBillingParser.BillingRecord::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String currency = billingCurrency(records, text(source.get("default_currency")));
            List<Map<String,Object>> sample = records.stream().limit(10).map(this::recordMap).toList();
            return new BillingPreview(fetched.httpStatus(), records.size(), amount, currency,
                    fetched.pages(), period.from(), period.to(), sample);
        } catch (Exception exception) {
            throw new IllegalStateException("供应商账单测试失败: " + safe(exception.getMessage()), exception);
        }
    }

    public BillingSyncSummary sync(String sourceId,
                                   OffsetDateTime from,
                                   OffsetDateTime to,
                                   String triggerType) {
        Map<String,Object> source = requireSource(sourceId);
        Period period = period(source, from, to);
        String runId = id();
        jdbc.update("""
            insert into provider_billing_sync_run(
              id,billing_source_id,trigger_type,status,period_start,period_end,started_at)
            values(?,?,?,'RUNNING',?,?,now())
            """, runId, sourceId, value(triggerType, "MANUAL"), period.from(), period.to());
        try {
            FetchResult fetched = fetch(source, period);
            List<ProviderBillingParser.BillingRecord> records = parse(source, fetched.content());
            int inserted = persistRecords(source, runId, records);
            BigDecimal amount = records.stream().map(ProviderBillingParser.BillingRecord::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String currency = billingCurrency(records, text(source.get("default_currency")));
            String reconciliationId = records.isEmpty() ? null
                    : upsertReconciliation(source, runId, period, records, amount, currency);
            String state = inserted == 0 ? "NO_CHANGE" : "SUCCEEDED";
            jdbc.update("""
                update provider_billing_sync_run set status=?,records_fetched=?,amount_fetched=?,currency=?,
                  completed_at=now(),execution_log=cast(? as jsonb),updated_at=now() where id=?
                """, state, records.size(), amount, currency,
                    write(List.of(Map.of("event", "BILLING_FETCHED", "pages", fetched.pages(),
                            "records", records.size(), "inserted", inserted))), runId);
            jdbc.update("""
                update provider_billing_source set
                  status=case when status in ('PAUSED','DISABLED') then status else 'ACTIVE' end,
                  last_success_at=now(),last_error=null,next_run_at=now()+cast(? as interval),updated_at=now()
                where id=?
                """, postgresInterval(text(source.get("schedule_expression"))), sourceId);
            Map<String,Object> after = requireSource(sourceId);
            audits.record("PROVIDER_BILLING_SYNC", "ProviderBillingSource", sourceId, source, after);
            return new BillingSyncSummary(runId, state, records.size(), amount, currency, reconciliationId);
        } catch (Exception exception) {
            jdbc.update("""
                update provider_billing_sync_run set status='FAILED',error_code='PROVIDER_BILLING_SYNC_FAILED',
                  error_message=?,completed_at=now(),updated_at=now() where id=?
                """, safe(exception.getMessage()), runId);
            jdbc.update("""
                update provider_billing_source set
                  status=case when status in ('PAUSED','DISABLED') then status else 'DEGRADED' end,
                  last_failure_at=now(),last_error=?,updated_at=now() where id=?
                """, safe(exception.getMessage()), sourceId);
            ensureAlert(source, exception);
            return new BillingSyncSummary(runId, "FAILED", 0, BigDecimal.ZERO,
                    text(source.get("default_currency")), null);
        }
    }

    @Scheduled(fixedDelayString = "${tokensea.provider-billing.scheduler-ms:300000}")
    public void processDueSources() {
        List<Map<String,Object>> due = jdbc.queryForList("""
            select * from provider_billing_source
            where status='ACTIVE' and (next_run_at is null or next_run_at<=now())
            order by next_run_at nulls first limit 10
            """);
        for (Map<String,Object> source : due) {
            try {
                Period period = period(source, null, null);
                sync(text(source.get("id")), period.from(), period.to(), "SCHEDULED");
            } catch (Exception ignored) {
                // Failure state and alert are persisted by sync().
            }
        }
    }

    private FetchResult fetch(Map<String,Object> source, Period period) throws Exception {
        ProviderInstance instance = providerInstance(source);
        String managedKey = connections.resolveManagedApiKey(instance);
        String adapter = text(source.get("adapter_code"));
        URI endpoint = URI.create(text(source.get("endpoint")));
        URI current = requestUri(endpoint, adapter, period, null, readMap(source.get("config")));
        String originalHost = current.getHost();
        ArrayNode combined = json.createArrayNode();
        int pages = 0;
        int totalBytes = 0;
        int statusCode = 200;
        while (current != null) {
            if (pages >= MAX_PAGES) throw new IllegalStateException("供应商账单分页超过 " + MAX_PAGES + " 页");
            HttpPage page = fetchPage(source, current, originalHost, instance, managedKey);
            pages++;
            statusCode = page.statusCode();
            totalBytes += page.body().length;
            if (totalBytes > MAX_RESPONSE_BYTES) throw new IllegalStateException("供应商账单累计响应超过 5MB");
            JsonNode root = json.readTree(page.body());
            JsonNode data = root.path("data");
            if ("OPENAI_COSTS_API".equals(adapter)) {
                if (!data.isArray()) throw new IllegalStateException("OpenAI Costs 响应缺少 data 数组");
                combined.addAll((ArrayNode) data);
                String next = root.path("next_page").asText("");
                current = blank(next) ? null : requestUri(endpoint, adapter, period, next, readMap(source.get("config")));
            } else {
                JsonNode records = genericRecords(root, readMap(source.get("config")));
                if (records.isArray()) combined.addAll((ArrayNode) records);
                String nextField = value(text(readMap(source.get("config")).get("nextPageField")), "next_page");
                String next = root.path(nextField).asText("");
                current = blank(next) ? null : requestUri(endpoint, adapter, period, next, readMap(source.get("config")));
            }
        }
        ObjectNode aggregate = json.createObjectNode();
        aggregate.set("data", combined);
        aggregate.put("_tokenseaPageCount", pages);
        return new FetchResult(statusCode, json.writeValueAsString(aggregate), pages);
    }

    private HttpPage fetchPage(Map<String,Object> source,
                               URI requested,
                               String originalHost,
                               ProviderInstance instance,
                               String managedKey) throws Exception {
        URI uri = requested;
        for (int redirect = 0; redirect <= 3; redirect++) {
            validateTarget(uri, source);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "TokenSea-BillingSync/1.0")
                    .GET();
            if (uri.getHost().equalsIgnoreCase(originalHost)) {
                connections.applyManagedAuthentication(builder, instance, managedKey);
            }
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (Set.of(301,302,303,307,308).contains(response.statusCode())) {
                if (redirect == 3) throw new IllegalStateException("供应商账单重定向次数超过限制");
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException("供应商账单重定向缺少 Location"));
                uri = uri.resolve(location);
                continue;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("供应商账单接口返回 HTTP " + response.statusCode());
            }
            if (response.body().length > MAX_RESPONSE_BYTES) throw new IllegalStateException("供应商账单响应超过 5MB");
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).contains("json")) {
                throw new IllegalStateException("供应商账单接口未返回 JSON: " + contentType);
            }
            return new HttpPage(response.statusCode(), response.body(), uri);
        }
        throw new IllegalStateException("供应商账单接口获取失败");
    }

    private URI requestUri(URI endpoint,
                           String adapter,
                           Period period,
                           String page,
                           Map<String,Object> config) {
        Map<String,String> query = parseQuery(endpoint.getRawQuery());
        if ("OPENAI_COSTS_API".equals(adapter)) {
            query.put("start_time", String.valueOf(period.from().toEpochSecond()));
            query.put("end_time", String.valueOf(period.to().toEpochSecond()));
            query.put("bucket_width", "1d");
            query.put("limit", "180");
            if (!blank(page)) query.put("page", page);
        } else {
            String startParam = value(text(config.get("startParam")), "start_time");
            String endParam = value(text(config.get("endParam")), "end_time");
            String pageParam = value(text(config.get("pageParam")), "page");
            boolean epoch = !"ISO_OFFSET_DATE_TIME".equals(text(config.get("timeFormat")));
            query.put(startParam, epoch ? String.valueOf(period.from().toEpochSecond()) : period.from().toString());
            query.put(endParam, epoch ? String.valueOf(period.to().toEpochSecond()) : period.to().toString());
            if (!blank(page)) query.put(pageParam, page);
        }
        try {
            String queryText = query.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .reduce((left, right) -> left + "&" + right).orElse("");
            return new URI(endpoint.getScheme(), endpoint.getAuthority(), endpoint.getPath(), queryText, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("供应商账单请求地址无效", exception);
        }
    }

    private Map<String,String> parseQuery(String rawQuery) {
        Map<String,String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return values;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(parts[0], parts.length == 2 ? parts[1] : "");
        }
        return values;
    }

    private JsonNode genericRecords(JsonNode root, Map<String,Object> config) {
        String path = value(text(config.get("recordsPath")), "data");
        JsonNode current = root;
        for (String part : path.split("\\.")) current = current == null ? null : current.path(part);
        return current;
    }

    private List<ProviderBillingParser.BillingRecord> parse(Map<String,Object> source, String content) {
        Map<String,Object> config = readMap(source.get("config"));
        if ("GENERIC_BILLING_JSON".equals(text(source.get("adapter_code")))) {
            config.put("recordsPath", "data");
        }
        return parser.parse(text(source.get("adapter_code")), content, text(source.get("endpoint")),
                text(source.get("default_currency")), config);
    }

    private String billingCurrency(List<ProviderBillingParser.BillingRecord> records, String fallback) {
        Set<String> currencies = new HashSet<>();
        for (ProviderBillingParser.BillingRecord record : records) currencies.add(record.currency());
        if (currencies.size() > 1) throw new IllegalStateException("单次账单同步返回多个币种，必须按币种拆分账单源");
        return currencies.isEmpty()
                ? value(fallback, "USD").toUpperCase(Locale.ROOT)
                : currencies.iterator().next();
    }

    @Transactional
    protected int persistRecords(Map<String,Object> source,
                                 String runId,
                                 List<ProviderBillingParser.BillingRecord> records) {
        int inserted = 0;
        for (ProviderBillingParser.BillingRecord record : records) {
            String rawJson = write(record.raw());
            String evidence = sha256(String.join("|",
                    text(source.get("id")), record.periodStart().toString(), record.periodEnd().toString(),
                    record.currency(), record.amount().toPlainString(), value(record.lineItem(), ""),
                    value(record.providerProjectId(), ""), rawJson).getBytes(StandardCharsets.UTF_8));
            int changed = jdbc.update("""
                insert into provider_billing_record(
                  id,billing_source_id,sync_run_id,provider_instance_id,period_start,period_end,currency,
                  amount,input_tokens,output_tokens,request_count,line_item,provider_model_name,
                  provider_project_id,source_ref,evidence_hash,raw_payload)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
                on conflict(billing_source_id,evidence_hash) do nothing
                """, id(), source.get("id"), runId, source.get("provider_instance_id"),
                    record.periodStart(), record.periodEnd(), record.currency(), record.amount(),
                    record.inputTokens(), record.outputTokens(), record.requestCount(), record.lineItem(),
                    record.providerModelName(), record.providerProjectId(), record.sourceRef(), evidence, rawJson);
            inserted += changed;
        }
        return inserted;
    }

    @Transactional
    protected String upsertReconciliation(Map<String,Object> source,
                                          String runId,
                                          Period period,
                                          List<ProviderBillingParser.BillingRecord> records,
                                          BigDecimal providerAmount,
                                          String currency) {
        String providerId = text(source.get("provider_instance_id"));
        Map<String,Object> internal = jdbc.queryForMap("""
            select coalesce(sum(coalesce(tokensea_fx_amount(cost_amount,currency,created_at,?),0)),0) internal_cost,
                   coalesce(sum(total_tokens),0) internal_tokens
            from usage_record
            where provider_id=? and created_at>=? and created_at<? and status='SUCCESS'
            """, currency, providerId, period.from(), period.to());
        BigDecimal internalCost = decimal(internal.get("internal_cost"));
        long internalTokens = ((Number) internal.get("internal_tokens")).longValue();
        BigDecimal difference = providerAmount.subtract(internalCost);
        Map<String,Object> classification = new LinkedHashMap<>();
        classification.put("amount", Map.of("provider", providerAmount, "internal", internalCost, "difference", difference));
        classification.put("token", Map.of("internal", internalTokens, "provider", "UNAVAILABLE", "difference", "UNAVAILABLE"));
        classification.put("source", Map.of("billingSourceId", source.get("id"), "syncRunId", runId,
                "recordCount", records.size()));
        List<Map<String,Object>> existing = jdbc.queryForList("""
            select * from provider_reconciliation
            where provider_instance_id=? and billing_source_id=?
              and period_start=?::date and period_end=?::date and status in ('OPEN','MATCHED','DISPUTED')
            order by created_at desc limit 1
            """, providerId, source.get("id"), period.from().toLocalDate(), period.to().minusNanos(1).toLocalDate());
        String id = existing.isEmpty() ? id() : text(existing.getFirst().get("id"));
        if (existing.isEmpty()) {
            jdbc.update("""
                insert into provider_reconciliation(
                  id,provider_instance_id,period_start,period_end,currency,internal_cost,provider_amount,
                  difference_amount,status,source_ref,token_difference,price_difference,
                  exchange_rate_difference,tax_difference,difference_classification,billing_source_id,billing_sync_run_id)
                values(?,?,?,?,?,?,?,?,?,?,0,?,0,0,cast(? as jsonb),?,?)
                """, id, providerId, period.from().toLocalDate(), period.to().minusNanos(1).toLocalDate(),
                    currency, internalCost, providerAmount, difference,
                    difference.signum() == 0 ? "MATCHED" : "OPEN", text(source.get("endpoint")),
                    difference, write(classification), source.get("id"), runId);
        } else {
            jdbc.update("""
                update provider_reconciliation set currency=?,internal_cost=?,provider_amount=?,difference_amount=?,
                  status=case when ?=0 then 'MATCHED' else 'OPEN' end,source_ref=?,price_difference=?,
                  difference_classification=cast(? as jsonb),billing_sync_run_id=?,updated_at=now()
                where id=?
                """, currency, internalCost, providerAmount, difference, difference,
                    text(source.get("endpoint")), difference, write(classification), runId, id);
        }
        return id;
    }

    private ProviderInstance providerInstance(Map<String,Object> source) {
        String id = text(source.get("provider_instance_id"));
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select id,provider_type,api_style,credential_ref,key_status,status
            from provider_instance where id=?
            """, id);
        if (rows.isEmpty()) throw new IllegalStateException("供应商账单来源绑定的渠道不存在");
        Map<String,Object> row = rows.getFirst();
        if (!Set.of("启用", "ACTIVE").contains(text(row.get("status")))) {
            throw new IllegalStateException("供应商账单来源绑定的渠道未启用");
        }
        ProviderInstance instance = new ProviderInstance();
        instance.setId(id);
        instance.setProviderType(text(row.get("provider_type")));
        instance.setApiStyle(text(row.get("api_style")));
        instance.setCredentialRef(text(row.get("credential_ref")));
        instance.setKeyStatus(text(row.get("key_status")));
        instance.setStatus(text(row.get("status")));
        return instance;
    }

    private Period period(Map<String,Object> source, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime end = to == null ? OffsetDateTime.now(ZoneOffset.UTC) : to;
        int lookbackDays = Math.max(1, Math.min(integer(readMap(source.get("config")).get("lookbackDays"), 7), 180));
        OffsetDateTime start = from == null ? end.minusDays(lookbackDays) : from;
        if (!end.isAfter(start)) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "账单同步周期无效");
        return new Period(start, end);
    }

    private void validateTarget(URI uri, Map<String,Object> source) throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalStateException("供应商账单接口必须使用无用户信息的 HTTPS 地址");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Set<String> sourceHosts = new HashSet<>(readStrings(source.get("official_hosts")));
        if (!sourceHosts.contains(host)) throw new IllegalStateException("供应商账单目标未列入来源官方域名");
        if (!proxyConfigured && (globalAllowedHosts.isEmpty() || !globalAllowedHosts.contains(host))) {
            throw new IllegalStateException("未配置出口代理时，供应商账单主机必须列入 TokenSea 出口硬边界");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalStateException("供应商账单接口解析到不允许的网络地址");
            }
        }
    }

    private void ensureAlert(Map<String,Object> source, Exception exception) {
        Integer exists = jdbc.queryForObject("""
            select count(*) from alert_event where alert_type='PROVIDER_BILLING_SYNC_FAILED'
              and resource_type='PROVIDER_BILLING_SOURCE' and resource_id=?
              and status in ('OPEN','ACKNOWLEDGED')
            """, Integer.class, source.get("id"));
        if (exists != null && exists > 0) return;
        jdbc.update("""
            insert into alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
            values(?,'PROVIDER_BILLING_SYNC_FAILED','WARNING','PROVIDER_BILLING_SOURCE',?,
              '供应商账单同步失败',cast(? as jsonb))
            """, id(), source.get("id"), write(Map.of("sourceName", source.get("name"),
                    "message", safe(exception.getMessage()))));
    }

    private Map<String,Object> requireSource(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from provider_billing_source where id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "供应商账单来源不存在");
        return rows.getFirst();
    }

    private Map<String,Object> recordMap(ProviderBillingParser.BillingRecord record) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("periodStart", record.periodStart());
        result.put("periodEnd", record.periodEnd());
        result.put("currency", record.currency());
        result.put("amount", record.amount());
        result.put("lineItem", record.lineItem());
        result.put("providerProjectId", record.providerProjectId());
        result.put("providerModelName", record.providerModelName());
        return result;
    }

    private List<String> readStrings(Object value) {
        if (value instanceof List<?> values) return values.stream().map(String::valueOf).map(String::toLowerCase).toList();
        try {
            return json.readValue(String.valueOf(value), json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String,Object> readMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?,?> map) {
            Map<String,Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try {
            return json.readValue(String.valueOf(value), new TypeReference<>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private Set<String> parseHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new HashSet<>();
        for (String host : value.split(",")) if (!host.isBlank()) result.add(host.trim().toLowerCase(Locale.ROOT));
        return Set.copyOf(result);
    }

    private Map<String,String> parseQueryPairs(String raw) {
        Map<String,String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            String[] values = pair.split("=", 2);
            result.put(values[0], values.length > 1 ? values[1] : "");
        }
        return result;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("供应商账单 JSON 序列化失败", exception);
        }
    }

    private String postgresInterval(String expression) {
        try {
            Duration duration = Duration.parse(value(expression, "P1D"));
            return duration.toSeconds() + " seconds";
        } catch (Exception exception) {
            return "86400 seconds";
        }
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private int integer(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        if (value == null) return "未知错误";
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private record Period(OffsetDateTime from, OffsetDateTime to) {}
    private record HttpPage(int statusCode, byte[] body, URI finalUri) {}
    private record FetchResult(int httpStatus, String content, int pages) {}
}
