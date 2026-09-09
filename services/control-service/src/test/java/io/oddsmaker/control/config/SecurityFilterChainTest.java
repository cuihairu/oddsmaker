package io.oddsmaker.control.config;

import io.oddsmaker.control.security.AdminTokenFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全过滤链构建测试：全链配置方法被调用且返回构建结果。
 */
@DisplayName("安全过滤链构建测试")
class SecurityFilterChainTest {

    @Test
    @DisplayName("securityFilterChain：CSRF/CORS/无状态会话/授权/头/异常处理全链配置")
    void buildsFullChain() throws Exception {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwkSetUri", "");
        ReflectionTestUtils.setField(config, "jwtIssuerUri", "");

        HttpSecurity http = mock(HttpSecurity.class);
        DefaultSecurityFilterChain chain = mock(DefaultSecurityFilterChain.class);
        when(http.csrf(any())).thenReturn(http);
        when(http.cors(any())).thenReturn(http);
        when(http.sessionManagement(any())).thenReturn(http);
        when(http.httpBasic(any())).thenReturn(http);
        when(http.formLogin(any())).thenReturn(http);
        when(http.logout(any())).thenReturn(http);
        when(http.oauth2ResourceServer(any())).thenReturn(http);
        when(http.authorizeHttpRequests(any())).thenReturn(http);
        when(http.addFilterBefore(any(jakarta.servlet.Filter.class), any(Class.class))).thenReturn(http);
        when(http.headers(any())).thenReturn(http);
        when(http.exceptionHandling(any())).thenReturn(http);
        when(http.build()).thenReturn(chain);

        AdminTokenFilter filter = new AdminTokenFilter(new org.springframework.mock.env.MockEnvironment());
        SecurityFilterChain result = config.securityFilterChain(http, filter);

        assertSame(chain, result);
        verify(http).csrf(any());
        verify(http).cors(any());
        verify(http).sessionManagement(any());
        verify(http).addFilterBefore(any(jakarta.servlet.Filter.class), any(Class.class));
        verify(http).build();
    }
}
