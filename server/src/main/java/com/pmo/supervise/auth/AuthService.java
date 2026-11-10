package com.pmo.supervise.auth;

import com.pmo.supervise.auth.dto.AuthUser;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.AuthSession;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.common.UserStatus;
import com.pmo.supervise.user.User;
import com.pmo.supervise.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 本地密码认证与会话（TECHNICAL §4.8）。
 *
 * <p>错误提示对“账号不存在 / 密码错误”完全一致，并仍然执行一次 BCrypt 比对，
 * 避免通过响应文案或响应耗时探测账号是否存在。停用账号在密码校验通过后才返回 403，
 * 因此不会把“账号存在”泄露给不知道密码的人。</p>
 */
@Service
public class AuthService {

    /** 账号不存在时用来消耗一次 BCrypt 比对的哑 hash（与 V2 种子账号同级 cost）。 */
    private static final String DUMMY_HASH =
            "$2a$10$J.jadwZEd6nV5yyUKWYiWe9sGMsAlj0OQt//TV9EQPdM1rY0GqmTO";

    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PASSWORD_MAX_LENGTH = 64;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginAuditService audit;
    private final ShanghaiClock clock;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder,
                       LoginAuditService audit, ShanghaiClock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AuthUser login(String username, String rawPassword, HttpServletRequest request) {
        String account = username == null ? "" : username.trim();
        Optional<User> found = account.isEmpty() ? Optional.empty() : users.findByUsername(account);
        User user = found.orElse(null);

        String hash = user == null || user.getPasswordHash() == null ? DUMMY_HASH : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(rawPassword == null ? "" : rawPassword, hash);
        if (user == null || user.getPasswordHash() == null || !passwordMatches) {
            audit.recordAnonymous(LoginAuditService.LOGIN_FAILED, "USER", null,
                    "账号或密码不正确" + (account.isEmpty() ? "" : "（账号：" + account + "）"), request);
            throw new BusinessException(ErrorCode.AUTH_BAD_CREDENTIALS, "账号或密码不正确");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            audit.record(user.getId(), LoginAuditService.LOGIN_DISABLED, "USER",
                    String.valueOf(user.getId()), "已停用账号尝试密码登录", request);
            throw new BusinessException(ErrorCode.AUTH_DISABLED);
        }
        return establish(user, request);
    }

    /** 登录成功后的唯一会话入口：钉钉扫码/免登也走这里，保证 last_login_at 与审计口径一致。 */
    @Transactional
    public AuthUser establish(User user, HttpServletRequest request) {
        AuthSession.rotateSessionId(request);   // 唯一换发点：表单登录 / 钉钉扫码 / 免登同口径
        user.setLastLoginAt(clock.now());
        User saved = users.save(user);
        Actor actor = new Actor(saved.getId(), saved.getRole(), saved.getName());
        SessionUser.signIn(request, actor, saved.isMustChangePassword());
        audit.record(saved.getId(), LoginAuditService.LOGIN_SUCCESS, "USER",
                String.valueOf(saved.getId()), null, request);
        return AuthUser.from(saved);
    }

    public void logout(HttpServletRequest request) {
        Actor actor = AuthSession.current(request);
        if (actor != null) {
            audit.record(actor.id(), LoginAuditService.LOGOUT, "USER", String.valueOf(actor.id()), null, request);
        }
        AuthSession.signOut(request);
    }

    /**
     * 当前用户。每次读取都回库取角色/状态：管理员刚改过角色时，
     * 旧会话不会继续按过期角色放行（会话里的 Actor 同步刷新）。
     */
    @Transactional
    public AuthUser me(Actor actor, HttpServletRequest request) {
        User user = users.findById(actor.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED, "登录状态已失效，请重新登录"));
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.AUTH_DISABLED);
        }
        Actor fresh = new Actor(user.getId(), user.getRole(), user.getName());
        if (!fresh.equals(actor)) {
            AuthSession.signIn(request, fresh);
        }
        return AuthUser.from(user);
    }

    @Transactional
    public void changePassword(long userId, String oldPassword, String newPassword, HttpServletRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED, "登录状态已失效，请重新登录"));

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(oldPassword == null ? "" : oldPassword, user.getPasswordHash())) {
            audit.record(userId, LoginAuditService.PASSWORD_CHANGE_REJECTED, "USER",
                    String.valueOf(userId), "原密码不正确", request);
            throw new BusinessException(ErrorCode.AUTH_OLD_PASSWORD_WRONG, "原密码不正确");
        }
        validatePasswordStrength(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_WEAK, "新密码不能与原密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        users.save(user);
        SessionUser.clearMustChangePassword(request);
        audit.record(userId, LoginAuditService.PASSWORD_CHANGED, "USER", String.valueOf(userId), null, request);
    }

    /**
     * 密码强度：8–64 位且同时包含字母与数字。
     * PRD 只约束了“12 位临时口令”，此处按技术设计的最小可校验口径实现。
     */
    public static void validatePasswordStrength(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_WEAK, "新密码至少 8 位，且需同时包含字母和数字");
        }
        if (password.length() > PASSWORD_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_WEAK, "新密码长度不能超过 64 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char ch : password.toCharArray()) {
            if (Character.isLetter(ch)) {
                hasLetter = true;
            } else if (Character.isDigit(ch)) {
                hasDigit = true;
            } else if (Character.isWhitespace(ch)) {
                throw new BusinessException(ErrorCode.AUTH_PASSWORD_WEAK, "新密码不能包含空格");
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_WEAK, "新密码至少 8 位，且需同时包含字母和数字");
        }
    }
}
