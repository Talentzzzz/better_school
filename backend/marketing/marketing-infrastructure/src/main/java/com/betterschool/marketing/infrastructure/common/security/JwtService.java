package com.betterschool.marketing.infrastructure.common.security;

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

/**
 * JWT 令牌服务：四个 app 读同一份 Nacos 配置（better-school-shared.yml 的 jwt.secret），
 * 所以单体签发的令牌在这里解得开。**本服务只验签、不签发**——签发是单体的登录链路，
 * 营销侧没有任何一处需要自己造令牌。
 */
@Slf4j
@Component
public class JwtService {

    private final SecretKey secretKey;

    public JwtService(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析令牌，无效或已过期返回 null（不抛异常） */
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

    @Data
    @AllArgsConstructor
    public static class JwtClaims {

        private String userId;
        private String role;
    }
}
