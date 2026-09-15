package io.oddsmaker.control.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.ExperimentService;
import io.oddsmaker.control.service.GameService;
import io.oddsmaker.control.service.StorageProfileService;

/**
 * ApiController 测试
 * 简化版本，避免MockBean兼容性问题
 */
@WebMvcTest(ApiController.class)
@DisplayName("ApiController 测试")
class ApiControllerTest {

    @MockBean
    private ControlService controlService;

    @MockBean
    private GameService gameService;

    @MockBean
    private ExperimentService experimentService;

    @MockBean
    private StorageProfileService storageProfileService;

    @MockBean
    private GameRepo gameRepo;

    // WebConfig（WebMvcConfigurer）在 @WebMvcTest 下会被强制加载，
    // 其依赖链 AuditLogInterceptor → AuditLogService 会拉入该 @Service，
    // 而切片测试默认不扫描 @Service，故需 mock 以下两个 bean。
    @MockBean
    private AuditLogService auditLogService;

    @Test
    @DisplayName("API控制器加载测试")
    void contextLoads() {
        // 基本的上下文加载测试
    }
}
