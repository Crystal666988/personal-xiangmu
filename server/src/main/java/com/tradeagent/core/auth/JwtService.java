package com.tradeagent.core.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与校验（HS256）。
 * 密钥经 feishu.jwt-secret 配置；未配置时用固定演示密钥并打警告（不得用于生产）。
 */
@Component
public class JwtService {

    @Value("${feishu.jwt-secret:tradeagent-demo-secret-key-change-me-0123456789abcdef}")
    private String secret = "tradeagent-demo-secret-key-change-me-0123456789abcdef";

    @Value("${feishu.jwt-expire-minutes:720}")
    private long expireMinutes = 720;

    private SecretKey key() {
        String s = secret != null ? secret : "tradeagent-demo-secret-key-change-me-0123456789abcdef";
        return Keys.hmacShaKeyFor(s.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenInfo(String userId, String name, String tenantId) {}

    public String issue(TokenInfo info) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(Map.of(
                        "userId", info.userId(),
                        "name", info.name(),
                        "tenantId", info.tenantId()))
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expireMinutes * 60_000))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** 校验并解析；无效/过期返回 null。 */
    public TokenInfo verify(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key()).build()
                    .parseClaimsJws(token).getBody();
            return new TokenInfo(
                    String.valueOf(claims.get("userId")),
                    String.valueOf(claims.get("name")),
                    String.valueOf(claims.get("tenantId")));
        } catch (Exception e) {
            return null;
        }
    }
}
