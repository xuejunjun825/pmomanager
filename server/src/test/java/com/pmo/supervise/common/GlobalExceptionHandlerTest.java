package com.pmo.supervise.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

/** 错误渲染契约（D-04）：方法不匹配是 405，不能落到兜底 500。 */
class GlobalExceptionHandlerTest {

    @Test
    void methodNotSupportedRenders405() {
        var response = new GlobalExceptionHandler().handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PATCH"), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }
}
