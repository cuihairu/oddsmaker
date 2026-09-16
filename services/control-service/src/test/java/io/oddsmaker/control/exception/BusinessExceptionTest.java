package io.oddsmaker.control.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 业务异常：四种构造器与状态码携带。
 */
@DisplayName("业务异常构造测试")
class BusinessExceptionTest {

    @Test
    @DisplayName("默认 400 + 自定义状态码构造器")
    void constructors() {
        assertEquals(HttpStatus.BAD_REQUEST, new BusinessException("m1").getStatus());
        assertEquals("m1", new BusinessException("m1").getMessage());

        assertEquals(HttpStatus.CONFLICT, new BusinessException("m2", HttpStatus.CONFLICT).getStatus());

        RuntimeException cause = new RuntimeException("root");
        BusinessException wrapped = new BusinessException("m3", cause);
        assertEquals(HttpStatus.BAD_REQUEST, wrapped.getStatus());
        assertSame(cause, wrapped.getCause());

        BusinessException full = new BusinessException("m4", cause, HttpStatus.UNPROCESSABLE_ENTITY);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, full.getStatus());
        assertSame(cause, full.getCause());
    }
}
