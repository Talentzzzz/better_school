package com.betterschool.config;

import com.betterschool.api.response.Response;
import com.betterschool.infrastructure.security.JwtAuthenticationFilter;
import com.betterschool.trigger.ws.ChatWsProtocol;
import com.betterschool.trigger.ws.ReviewWsProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 无状态 JWT 安全配置：白名单接口放行，其余需登录；401/403 返回统一 Response JSON
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/user/send-code",
                                "/api/user/register",
                                "/api/user/login"
                        ).permitAll()
                        // 公开资料免登录（作者主页/「我的」页资料区）；PUT /profile 与 /privacy 仍走 anyRequest().authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/user/*/profile").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/community/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/trade/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/errand/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/search/**").permitAll()
                        // WS 握手鉴权不走 JWT：query 一次性票据由各端点握手拦截器原子取删校验（票 04/票 10）
                        .requestMatchers(ChatWsProtocol.ENDPOINT).permitAll()
                        .requestMatchers(ReviewWsProtocol.ENDPOINT).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> writeJson(
                                response, HttpServletResponse.SC_UNAUTHORIZED, Response.fail("401", "未登录或登录已过期")))
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeJson(
                                response, HttpServletResponse.SC_FORBIDDEN, Response.fail("403", "无权限"))))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, Response<Void> body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
