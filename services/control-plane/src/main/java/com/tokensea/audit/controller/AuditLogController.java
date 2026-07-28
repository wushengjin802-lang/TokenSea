package com.tokensea.audit.controller;

import com.tokensea.common.ApiResponse;
import com.tokensea.common.PageQuery;
import com.tokensea.common.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditLogController {
    private static final String AUDIT_PROJECTION = """
        select
          a.id,
          a.actor_id,
          case
            when a.actor_id = 'SYSTEM' then '系统任务'
            when nullif(btrim(u.display_name), '') is not null then btrim(u.display_name)
            when nullif(btrim(u.username), '') is not null then btrim(u.username)
            when a.actor_name like 'Identity[%ADMIN%' then '平台管理员'
            when a.actor_name like 'Identity[%' then '已登录用户'
            when nullif(btrim(a.actor_name), '') is not null then btrim(a.actor_name)
            else '未知操作人'
          end as actor_name,
          a.action,
          a.object_type,
          a.object_id,
          a.before_value,
          a.after_value,
          case
            when nullif(btrim(a.ip_address), '') is null then
              case when a.actor_id = 'SYSTEM' then '系统任务' else '未记录' end
            when btrim(a.ip_address) in ('::1', '0:0:0:0:0:0:0:1') then '127.0.0.1'
            when lower(btrim(a.ip_address)) like '::ffff:%' then substring(btrim(a.ip_address) from 8)
            else btrim(a.ip_address)
          end as ip_address,
          a.user_agent,
          a.created_at,
          a.updated_at
        from audit_log a
        left join user_account u on u.id = a.actor_id
        """;

    private final JdbcTemplate jdbc;

    public AuditLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(@RequestParam(required = false) Integer page,
                                                              @RequestParam(required = false) Integer size,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(required = false) String sort,
                                                              @RequestParam(required = false) String order) {
        PageQuery paging = PageQuery.of(page, size, sort, order, Map.ofEntries(
                Map.entry("id", "a.id"),
                Map.entry("actorId", "a.actor_id"),
                Map.entry("actorName", "actor_name"),
                Map.entry("action", "a.action"),
                Map.entry("objectType", "a.object_type"),
                Map.entry("objectName", "a.object_type"),
                Map.entry("objectId", "a.object_id"),
                Map.entry("ipAddress", "a.ip_address"),
                Map.entry("clientName", "a.user_agent"),
                Map.entry("createdAt", "a.created_at"),
                Map.entry("updatedAt", "a.updated_at")
        ), "createdAt", "desc");
        String q = keyword == null || keyword.isBlank()
                ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String filter = """
             where (?::text is null
                or lower(coalesce(a.actor_id,'')) like ?
                or lower(coalesce(a.actor_name,'')) like ?
                or lower(coalesce(u.display_name,'')) like ?
                or lower(coalesce(u.username,'')) like ?
                or lower(coalesce(a.action,'')) like ?
                or lower(coalesce(a.object_type,'')) like ?
                or lower(coalesce(a.object_id,'')) like ?
                or lower(coalesce(a.ip_address,'')) like ?
                or lower(coalesce(a.user_agent,'')) like ?)
            """;
        Object[] filters = {q, q, q, q, q, q, q, q, q, q};
        List<Map<String, Object>> rows = jdbc.queryForList(
                AUDIT_PROJECTION + filter + " order by " + paging.sortColumn() + " " + paging.direction()
                        + ("a.id".equals(paging.sortColumn()) ? "" : ", a.id " + paging.direction())
                        + " limit ? offset ?",
                append(filters, paging.size(), paging.offset()));
        Long total = jdbc.queryForObject("""
            select count(*) from audit_log a
            left join user_account u on u.id = a.actor_id
            """ + filter, Long.class, filters);
        return ApiResponse.ok(new PageResult<>(rows, total == null ? 0 : total, paging.page(), paging.size()));
    }

    private static Object[] append(Object[] values, Object... extra) {
        Object[] result = new Object[values.length + extra.length];
        System.arraycopy(values, 0, result, 0, values.length);
        System.arraycopy(extra, 0, result, values.length, extra.length);
        return result;
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable("id") String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(AUDIT_PROJECTION + " where a.id = ?", id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审计记录不存在");
        }
        return ApiResponse.ok(rows.getFirst());
    }
}
