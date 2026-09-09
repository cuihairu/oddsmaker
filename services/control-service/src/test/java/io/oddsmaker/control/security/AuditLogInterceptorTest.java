package io.oddsmaker.control.security;

import io.oddsmaker.control.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 审计拦截器测试：@Auditable 注解方法记录审计日志，非控制器/无注解跳过。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计拦截器测试")
class AuditLogInterceptorTest {

    @Auditable(action = io.oddsmaker.control.jpa.AuditLogEntity.AuditAction.CREATE,
            resourceType = "game")
    public void annotatedHandler(String gameId) {
    }

    public void plainHandler() {
    }

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AuditLogInterceptor interceptor;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("preHandle 注入开始时间与请求 ID 并放行")
    void preHandleInjectsAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertTrue(request.getAttribute("auditStartTime") instanceof Long);
        assertTrue(String.valueOf(request.getAttribute("auditRequestId")).contains("-"));
    }

    @Test
    @DisplayName("afterCompletion：@Auditable 方法写审计日志")
    void recordsAuditForAnnotatedMethod() throws Exception {
        SecurityContextHolder.getContext()
            .setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken("tester", "pw"));
        HandlerMethod handler = new HandlerMethod(this,
            AuditLogInterceptorTest.class.getMethod("annotatedHandler", String.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        interceptor.preHandle(request, new MockHttpServletResponse(), handler);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);

        verify(auditLogService).log(any(io.oddsmaker.control.jpa.AuditLogEntity.class));
    }

    @Test
    @DisplayName("afterCompletion：非 HandlerMethod 与无注解方法跳过")
    void skipsNonAuditable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        HandlerMethod plain = new HandlerMethod(this,
            AuditLogInterceptorTest.class.getMethod("plainHandler"));
        interceptor.afterCompletion(request, new MockHttpServletResponse(), plain, null);

        verify(auditLogService, never()).log(any(io.oddsmaker.control.jpa.AuditLogEntity.class));
    }

    @Test
    @DisplayName("afterCompletion：异常不向上抛出")
    void swallowsAuditFailures() throws Exception {
        HandlerMethod handler = new HandlerMethod(this,
            AuditLogInterceptorTest.class.getMethod("annotatedHandler", String.class));
        MockHttpServletRequest request = new MockHttpServletRequest();  // 无 startTime 属性
        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, new RuntimeException("x"));
        // 不抛异常即通过（内部 catch 记日志）
    }
}
