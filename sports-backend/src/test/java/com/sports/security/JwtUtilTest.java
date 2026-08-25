package com.sports.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具类纯逻辑测试（无需 Spring 容器）。
 * 覆盖：令牌生成、用户名/用户ID/角色提取、校验、过期判断。
 */
class JwtUtilTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private static final long EXPIRATION = 3600_000L;        // 1 小时
    private static final long REFRESH_EXPIRATION = 86_400_000L;

    private JwtUtil newUtil(long expiration) {
        return new JwtUtil(SECRET, expiration, REFRESH_EXPIRATION);
    }

    @Test
    void generateAccessToken_and_extractClaims() {
        JwtUtil util = newUtil(EXPIRATION);
        String token = util.generateAccessToken(42L, "teacher", "ROLE_TEACHER");

        assertNotNull(token);
        assertEquals("teacher", util.extractUsername(token));
        assertEquals(42L, util.extractUserId(token));
        assertEquals("ROLE_TEACHER", util.extractRole(token));
    }

    @Test
    void generateRefreshToken_and_extract() {
        JwtUtil util = newUtil(EXPIRATION);
        String token = util.generateRefreshToken(7L, "admin");

        assertEquals("admin", util.extractUsername(token));
        assertEquals(7L, util.extractUserId(token));
    }

    @Test
    void validateToken_valid() {
        JwtUtil util = newUtil(EXPIRATION);
        String token = util.generateAccessToken(1L, "student", "ROLE_STUDENT");
        assertTrue(util.validateToken(token));
    }

    @Test
    void validateToken_invalid() {
        JwtUtil util = newUtil(EXPIRATION);
        // 篡改令牌
        String bad = "not.a.valid.jwt.token";
        assertFalse(util.validateToken(bad));
    }

    @Test
    void isTokenExpired_freshTokenNotExpired() {
        JwtUtil util = newUtil(EXPIRATION);
        String token = util.generateAccessToken(1L, "u", "ROLE");
        assertFalse(util.isTokenExpired(token));
    }

    @Test
    void isTokenExpired_expiredToken() {
        // 负过期时间 => 签发即过期
        JwtUtil util = newUtil(-1000L);
        String token = util.generateAccessToken(1L, "u", "ROLE");
        assertTrue(util.isTokenExpired(token));
        assertFalse(util.validateToken(token));
    }

    @Test
    void validateToken_handlesRandomStringGracefully() {
        JwtUtil util = newUtil(EXPIRATION);
        assertFalse(util.validateToken(""));
        assertFalse(util.validateToken(null));
        // 确保不会抛出非 JwtException 的异常
        assertDoesNotThrow(() -> util.validateToken("garbage"));
    }
}
