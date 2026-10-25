package com.tradeagent.core.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    @Test
    @DisplayName("JWT 签发→校验: 载荷完整往返")
    void issueAndVerify() {
        JwtService service = new JwtService();
        String token = service.issue(new JwtService.TokenInfo("ou_123", "谢梓浩", "1001"));
        System.out.println("[JWT token] " + token);
        assertNotNull(token);

        JwtService.TokenInfo info = service.verify(token);
        assertNotNull(info, "签发的 token 必须能通过校验");
        assertEquals("ou_123", info.userId());
        assertEquals("谢梓浩", info.name());
        assertEquals("1001", info.tenantId());
    }

    @Test
    @DisplayName("篡改/伪造 token 校验失败返回 null")
    void tamperedTokenRejected() {
        JwtService service = new JwtService();
        String token = service.issue(new JwtService.TokenInfo("u", "n", "t"));
        assertNull(service.verify(token + "x"));        // 篡改
        assertNull(service.verify("fake.token.sig"));   // 伪造
        assertNull(service.verify(null));               // 空
    }
}
