package com.pmo.supervise.user;

import com.pmo.supervise.auth.LoginAuditService;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.PageResponse;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.UserStatus;
import com.pmo.supervise.task.TaskAccessGuard;
import com.pmo.supervise.user.dto.UserRow;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户管理（AC-2 / AC-18）：分页、角色、状态、重置密码。
 *
 * <p>角色只能由管理员手工分配——钉钉只提供身份，不提供角色，因此全类没有"按钉钉同步角色"的入口。
 * 停用自己一律 409：管理员一次误操作就会把自己锁在门外。</p>
 */
@Service
public class UserService {

    /** 去掉 0/O/1/l/I 等易混字符，临时口令要能照着念给本人。 */
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final int TEMPORARY_PASSWORD_LENGTH = 12;

    private final UserRepository users;
    private final TaskAccessGuard accessGuard;
    private final PasswordEncoder passwordEncoder;
    private final LoginAuditService audit;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserRepository users, TaskAccessGuard accessGuard, PasswordEncoder passwordEncoder,
                       LoginAuditService audit) {
        this.users = users;
        this.accessGuard = accessGuard;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserRow> page(Actor actor, String keyword, Role role, UserStatus status, int page, int size) {
        accessGuard.requireAdmin(actor);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("name")), like)));
            }
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<User> result = users.findAll(spec,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.ASC, "id")));
        return PageResponse.of(result.getContent().stream().map(UserRow::from).toList(),
                result.getTotalElements(), safePage, safeSize);
    }

    /** 下拉数据源：默认只给启用账号；停用账号只在"包含停用"时出现。 */
    @Transactional(readOnly = true)
    public List<UserRow> options(boolean includeDisabled) {
        List<User> list = includeDisabled
                ? users.findAllByOrderByNameAsc()
                : users.findAllByStatusOrderByNameAsc(UserStatus.ACTIVE);
        return list.stream().map(UserRow::from).toList();
    }

    @Transactional
    public UserRow updateRole(long id, Role role, Actor actor, HttpServletRequest request) {
        accessGuard.requireAdmin(actor);
        if (role == null) {
            throw BusinessException.validation("role", "角色不能为空");
        }
        User user = load(id);
        Role before = user.getRole();
        user.setRole(role);
        User saved = users.save(user);
        audit.record(actor.id(), LoginAuditService.USER_ROLE_CHANGED, "USER", String.valueOf(id),
                "角色 " + label(before) + " → " + role.label(), request);
        return UserRow.from(saved);
    }

    @Transactional
    public UserRow updateStatus(long id, UserStatus status, Actor actor, HttpServletRequest request) {
        accessGuard.requireAdmin(actor);
        if (status == null) {
            throw BusinessException.validation("status", "状态不能为空");
        }
        if (status == UserStatus.DISABLED && id == actor.id()) {
            throw new BusinessException(ErrorCode.SELF_DISABLE_FORBIDDEN, "不能停用自己的账号");
        }
        User user = load(id);
        UserStatus before = user.getStatus();
        user.setStatus(status);
        User saved = users.save(user);
        audit.record(actor.id(), LoginAuditService.USER_STATUS_CHANGED, "USER", String.valueOf(id),
                "状态 " + label(before) + " → " + status.label(), request);
        return UserRow.from(saved);
    }

    /**
     * 重置为 12 位一次性临时口令，并强制下次登录改密（must_change_password=1）。
     * 返回值只出现这一次，不落库明文、不再可查。
     */
    @Transactional
    public String resetPassword(long id, Actor actor, HttpServletRequest request) {
        accessGuard.requireAdmin(actor);
        User user = load(id);
        String temporaryPassword = generateTemporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        users.save(user);
        audit.record(actor.id(), LoginAuditService.PASSWORD_RESET, "USER", String.valueOf(id),
                "管理员重置临时口令（12 位，首次登录强制改密）", request);
        return temporaryPassword;
    }

    private User load(long id) {
        return users.findById(id).orElseThrow(() -> BusinessException.notFound("用户不存在"));
    }

    private static String label(Enum<?> value) {
        if (value instanceof Role role) {
            return role.label();
        }
        if (value instanceof UserStatus status) {
            return status.label();
        }
        return value == null ? "-" : value.name();
    }

    String generateTemporaryPassword() {
        String alphabet = UPPER + LOWER + DIGITS;
        char[] chars = new char[TEMPORARY_PASSWORD_LENGTH];
        // 先各放一个字符类，保证一定含大写、小写、数字（与 AuthService 的强度规则一致）
        chars[0] = UPPER.charAt(random.nextInt(UPPER.length()));
        chars[1] = LOWER.charAt(random.nextInt(LOWER.length()));
        chars[2] = DIGITS.charAt(random.nextInt(DIGITS.length()));
        for (int i = 3; i < chars.length; i++) {
            chars[i] = alphabet.charAt(random.nextInt(alphabet.length()));
        }
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }
}
