package com.pmo.supervise.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录/权限相关审计（audit_logs）。AC-18 要求“被拒绝的登录尝试必须留痕”，
 * 所以审计写入永不向外抛异常：审计失败只记日志，不改变登录结果。
 */
@Service
public class LoginAuditService {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGIN_DISABLED = "LOGIN_DISABLED";
    public static final String LOGOUT = "LOGOUT";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_CHANGE_REJECTED = "PASSWORD_CHANGE_REJECTED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String DINGTALK_LOGIN = "DINGTALK_LOGIN";
    public static final String DINGTALK_STATE_INVALID = "DINGTALK_STATE_INVALID";
    public static final String DINGTALK_CODE_INVALID = "DINGTALK_CODE_INVALID";
    public static final String DINGTALK_NOT_CONFIGURED = "DINGTALK_NOT_CONFIGURED";
    public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";
    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";

    private static final Logger log = LoggerFactory.getLogger(LoginAuditService.class);

    private final JdbcTemplate jdbc;

    public LoginAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * REQUIRES_NEW：被拒绝的登录尝试是在业务事务里抛异常前写下的，
     * 若与业务共用事务会被回滚掉（AC-18 要求“被拒必须留痕”），故审计自成一个事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String action, String targetType, String targetId,
                       String detail, HttpServletRequest request) {
        write(actorId, action, targetType, targetId, detail, request);
    }

    /** 未登录场景（登录失败、state 伪造、未配置）没有 actor，actorId 传 null。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAnonymous(String action, String targetType, String targetId,
                                String detail, HttpServletRequest request) {
        write(null, action, targetType, targetId, detail, request);
    }

    private void write(Long actorId, String action, String targetType, String targetId,
                       String detail, HttpServletRequest request) {
        try {
            jdbc.update("""
                    INSERT INTO audit_logs (actor_id, action, target_type, target_id, detail, ip)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, actorId, cut(action, 48), cut(targetType, 32), cut(targetId, 64),
                    cut(detail, 1000), cut(clientIp(request), 45));
        } catch (RuntimeException ex) {
            log.warn("audit write failed action={} target={}:{}", action, targetType, targetId, ex);
        }
    }

    /** 取真实客户端地址；Nginx 反代时 forward-headers-strategy 已把 X-Forwarded-For 落到 remoteAddr。 */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = request.getRemoteAddr();
        return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
