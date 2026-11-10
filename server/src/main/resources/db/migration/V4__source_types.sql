-- 来源类型配置表（TECHNICAL §3.1 / AC-1）
-- code 是业务主键：tasks.source_type 直接外键引用它，删除被引用的来源在库层就失败。
CREATE TABLE source_types (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(16)     NOT NULL,
  name       VARCHAR(10)     NOT NULL,
  form       VARCHAR(16)     NOT NULL,
  color      VARCHAR(7)      NOT NULL,
  sort_no    INT             NOT NULL DEFAULT 0,
  enabled    TINYINT(1)      NOT NULL DEFAULT 1,
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_source_code (code),
  UNIQUE KEY uk_source_name (name),
  CONSTRAINT ck_source_form CHECK (form IN ('MEETING', 'NOTE')),
  CONSTRAINT ck_source_sort CHECK (sort_no BETWEEN 0 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 内置三条：颜色与 r1 的三枚标签逐像素一致（DESIGN §1.2），升级后页面颜色不跳变。
INSERT INTO source_types (code, name, form, color, sort_no, enabled) VALUES
  ('MEETING', '会议决议', 'MEETING', '#1B4D8F', 0, 1),
  ('PROJECT', '项目任务', 'NOTE',    '#6941C6', 1, 1),
  ('DAILY',   '日常待办', 'NOTE',    '#475467', 2, 1);

-- 拆除按枚举写死的来源 CHECK：自定义编码必须能写库，只保留「会议两引用与说明互斥」的结构约束。
ALTER TABLE tasks DROP CHECK ck_tasks_source;

ALTER TABLE tasks ADD CONSTRAINT ck_tasks_source CHECK (
  (source_meeting_id IS NOT NULL AND source_decision_item_id IS NOT NULL AND source_note IS NULL)
  OR (source_meeting_id IS NULL AND source_decision_item_id IS NULL AND source_note IS NOT NULL)
);

-- 删除被引用来源在库层失败（ON DELETE RESTRICT），业务层再按 409 给出可读文案。
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_source_type
  FOREIGN KEY (source_type) REFERENCES source_types (code)
  ON UPDATE RESTRICT ON DELETE RESTRICT;
