package com.pmo.supervise.config;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.AuthSession;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把会话身份解析成 Controller 的 {@link Actor} 参数。
 * 未登录统一抛 401 AUTH_REQUIRED；Service 层只接收 Actor。
 */
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Actor actor = request == null ? null : AuthSession.current(request);
        if (actor == null) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        return actor;
    }
}
