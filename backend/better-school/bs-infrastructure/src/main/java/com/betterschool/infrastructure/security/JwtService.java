package com.betterschool.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 令牌服务（基础设施组件）：签发与解析登录令牌。
 * 签发能力经 user 域仓储（IUserRepository.generateToken）暴露给登录服务，解析能力供 JwtAuthenticationFilter 使用
 */
@Slf4j
@Component
public class JwtService {

    private final SecretKey secretKey;
    private final long expireMillis;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expire-days:7}") long expireDays) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireDays * 24 * 60 * 60 * 1000L;
    }

    public String generateToken(String userId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)  // userId 已是 UUID 字符串
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析令牌，无效或已过期返回 null（不抛异常）
     */
    public JwtClaims parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new JwtClaims(claims.getSubject(), claims.get("role", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 令牌载荷
     */
    @Data
    @AllArgsConstructor
    public static class JwtClaims {

        private String userId;
        private String role;
    }
}
