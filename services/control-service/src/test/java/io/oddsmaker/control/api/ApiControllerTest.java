package io.oddsmaker.control.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.oddsmaker.control.jpa.GameRepo;
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

    @MockitoBean
    private ControlService controlService;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private ExperimentService experimentService;

    @MockitoBean
    private StorageProfileService storageProfileService;

    @MockitoBean
    private GameRepo gameRepo;

    @Test
    @DisplayName("API控制器加载测试")
    void contextLoads() {
        // 基本的上下文加载测试
    }
}
