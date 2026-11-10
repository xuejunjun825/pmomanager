-- 项目主数据（TECHNICAL §1 / AC-1、AC-4、AC-8）
-- 名称全表唯一（含停用，库层大小写不敏感）；编码由业务生成（P%05d），库层只要求唯一。
CREATE TABLE projects (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(16)     NOT NULL,
  name       VARCHAR(40)     NOT NULL COMMENT '业务上限 2–20 字（码点）由服务端判定；列宽留余量避免截断 500',
  sort_no    INT             NOT NULL DEFAULT 0,
  enabled    TINYINT(1)      NOT NULL DEFAULT 1,
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_code (code),
  UNIQUE KEY uk_project_name (name),
  KEY idx_projects_sort (sort_no, id),
  CONSTRAINT ck_project_sort CHECK (sort_no BETWEEN 0 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 任务只增加一个可空列；不触碰任何既有行（AC-3 零迁移）。
ALTER TABLE tasks
  ADD COLUMN source_project_id BIGINT UNSIGNED NULL AFTER source_note,
  ADD KEY idx_tasks_source_project (source_project_id),
  ADD CONSTRAINT fk_tasks_source_project FOREIGN KEY (source_project_id)
    REFERENCES projects (id) ON UPDATE RESTRICT ON DELETE RESTRICT;

-- 三态互斥：恰好一组非空（会议两字段 / 说明 / 项目）。只兜结构，不绑定形态编码——
-- 形态与字段的配对由 TaskService 单点判定，改形因此不需要改写历史行（AC-6）。
ALTER TABLE tasks DROP CHECK ck_tasks_source;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_source CHECK (
  (source_meeting_id IS NOT NULL AND source_decision_item_id IS NOT NULL
     AND source_note IS NULL AND source_project_id IS NULL)
  OR (source_meeting_id IS NULL AND source_decision_item_id IS NULL
     AND source_note IS NOT NULL AND source_project_id IS NULL)
  OR (source_meeting_id IS NULL AND source_decision_item_id IS NULL
     AND source_note IS NULL AND source_project_id IS NOT NULL)
);

-- 形态枚举扩三值（AC-2）。
ALTER TABLE source_types DROP CHECK ck_source_form;
ALTER TABLE source_types ADD CONSTRAINT ck_source_form
  CHECK (form IN ('MEETING', 'PROJECT', 'NOTE'));
