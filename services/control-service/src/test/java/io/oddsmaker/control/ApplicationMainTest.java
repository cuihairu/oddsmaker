package io.oddsmaker.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

/**
 * 启动入口 main：经 mockStatic 验证委托 SpringApplication.run，不真启 context。
 */
@DisplayName("控制面启动入口测试")
class ApplicationMainTest {

    @Test
    @DisplayName("main 委托 SpringApplication.run 启动")
    void mainDelegatesToSpringRun() {
        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            Application.main(new String[0]);
            mocked.verify(() -> SpringApplication.run(Application.class, new String[0]));
        }
    }
}
