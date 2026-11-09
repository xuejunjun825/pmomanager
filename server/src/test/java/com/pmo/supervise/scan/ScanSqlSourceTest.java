package com.pmo.supervise.scan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单源口径门禁（D-02 / TECHNICAL §4.1）：
 * 扫描 SELECT 必须复用 TaskSql 常量，不得再手写第二份逾期/未完成谓词。
 */
class ScanSqlSourceTest {

    private static final Path SCAN_SOURCE =
            Path.of("src/main/java/com/pmo/supervise/scan/DailyScanJob.java");

    @Test
    void scanReusesTaskSqlPredicates() throws IOException {
        String source = Files.readString(SCAN_SOURCE);

        assertThat(source).contains("TaskSql.DUE_SOON", "TaskSql.OVERDUE");
        assertThat(source).doesNotContain("status NOT IN ('DONE','CANCELLED')");
        assertThat(source).doesNotContain("status IN ('PENDING','IN_PROGRESS','PENDING_ACCEPT')");
    }
}
