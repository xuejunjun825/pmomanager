package com.pmo.supervise.user;

import com.pmo.supervise.common.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 用户仓储。分页筛选用 Specification，避免可选条件在 JPQL 里写成
 * {@code :role is null} 那种依赖类型推断的写法。
 */
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByUsername(String username);

    Optional<User> findByDingtalkUnionId(String dingtalkUnionId);

    List<User> findAllByStatusOrderByNameAsc(UserStatus status);

    List<User> findAllByOrderByNameAsc();
}
