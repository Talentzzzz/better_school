package com.betterschool.marketing.config;

import com.betterschool.marketing.api.common.response.Response;
import com.betterschool.marketing.infrastructure.common.security.JwtAuthenticationFilter;
import com.betterschool.marketing.types.common.ResponseCode;
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
 * 无状态 JWT 安全配置（写法照单体；票 07 合并后取 lottery 版——它已是超集）。
 * <p>
 * **免登录白名单只有两条**：{@code GET /api/lottery/activity} 与 {@code GET /api/lottery/prizes}——
 * 访客要能看 banner 与奖品目录，个人区块由前端显示「登录后参与」；积分接口全部需登录。
 * 其余全需登录；开桶接口另有 ADMIN 校验（控制器内判 role，不进白名单）。
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
                        .requestMatchers(HttpMethod.GET, "/api/lottery/activity", "/api/lottery/prizes").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> writeJson(
                                response, HttpServletResponse.SC_UNAUTHORIZED,
                                Response.fail(ResponseCode.UNAUTHORIZED.getCode(), ResponseCode.UNAUTHORIZED.getInfo())))
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeJson(
                                response, HttpServletResponse.SC_FORBIDDEN,
                                Response.fail(ResponseCode.FORBIDDEN.getCode(), ResponseCode.FORBIDDEN.getInfo()))))
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
