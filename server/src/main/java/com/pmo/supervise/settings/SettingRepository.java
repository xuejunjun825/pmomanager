package com.pmo.supervise.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** settings 表仓储：键即主键，读出后由 SettingService 统一解析。 */
public interface SettingRepository extends JpaRepository<Setting, String> {

    Optional<Setting> findByKey(String key);
}
