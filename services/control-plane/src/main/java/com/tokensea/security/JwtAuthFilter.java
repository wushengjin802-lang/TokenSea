package com.tokensea.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    public JwtAuthFilter(JwtService jwtService, JdbcTemplate jdbc) { this.jwtService = jwtService; this.jdbc = jdbc; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            JwtService.Identity tokenIdentity;
            try {
                tokenIdentity = jwtService.identity(auth.substring(7));
            } catch (RuntimeException invalidToken) {
                // 仅令牌解析、签名或过期问题返回 401。数据库故障必须继续上抛，避免被误报为登录失效。
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            List<String> canonicalUserIds = jdbc.queryForList("""
                select id from user_account
                where status='ACTIVE' and (id=? or username=?)
                order by case when id=? then 0 else 1 end
                limit 1
                """, String.class, tokenIdentity.userId(), tokenIdentity.userId(), tokenIdentity.userId());
            if (canonicalUserIds.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            String canonicalUserId = canonicalUserIds.get(0);
            List<String> roles = jdbc.queryForList("""
                select r.code from role r join user_role ur on ur.role_id=r.id
                where ur.user_id=? and r.status='ACTIVE' order by r.code
                """, String.class, canonicalUserId);
            List<String> tenantIds = jdbc.queryForList("""
                select tenant_id from user_tenant where user_id=? and status='ACTIVE'
                """, String.class, canonicalUserId);
            JwtService.Identity identity = new JwtService.Identity(canonicalUserId, roles, tenantIds);
            List<SimpleGrantedAuthority> authorities = identity.roles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
            UsernamePasswordAuthenticationToken a = new UsernamePasswordAuthenticationToken(identity, null, authorities);
            a.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(a);
        }
        chain.doFilter(request, response);
    }
}
