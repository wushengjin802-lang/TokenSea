package com.tokensea.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokensea.audit.entity.AuditLog;
import com.tokensea.audit.mapper.AuditLogMapper;
import com.tokensea.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Service
public class AuditService {
    private final AuditLogMapper mapper;
    private final ObjectMapper json;

    public AuditService(AuditLogMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public <T> T snapshot(T value, Class<T> type) {
        return value == null ? null : json.convertValue(value, type);
    }

    public void record(String action, String type, String id, Object before, Object after) {
        try {
            AuditLog log = new AuditLog();
            log.setId(UUID.randomUUID().toString().replace("-", ""));
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof JwtService.Identity identity) {
                log.setActorId(identity.userId());
                log.setActorName(authentication.getName());
            } else {
                log.setActorId("SYSTEM");
                log.setActorName("系统任务");
            }
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                HttpServletRequest request = attributes.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                String realIp = request.getHeader("X-Real-IP");
                String sourceIp = firstNonBlank(forwarded, realIp, request.getRemoteAddr());
                log.setIpAddress(normalizeIp(sourceIp));
                log.setUserAgent(request.getHeader("User-Agent"));
            }
            log.setAction(action);
            log.setObjectType(type);
            log.setObjectId(id);
            log.setBeforeValue(before == null ? null : json.writeValueAsString(before));
            log.setAfterValue(after == null ? null : json.writeValueAsString(after));
            mapper.insert(log);
        } catch (Exception e) {
            throw new IllegalStateException("关键操作审计写入失败", e);
        }
    }

    static String normalizeIp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String ip = raw.split(",", 2)[0].trim();
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        if (ip.regionMatches(true, 0, "::ffff:", 0, 7)) return ip.substring(7);
        return ip;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
