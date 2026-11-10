-- 任务进度（append-only）与 @ 关系（TECHNICAL §3.3 / AC-6、AC-9）
-- 进度不写 task_events、不改 tasks.status/due_date/owner_id：看板与矩阵口径零改动（AC-12）。
CREATE TABLE task_progress (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id    BIGINT UNSIGNED NOT NULL,
  author_id  BIGINT UNSIGNED NOT NULL,
  content    VARCHAR(500)    NOT NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_progress_task (task_id, id),
  CONSTRAINT fk_progress_task FOREIGN KEY (task_id) REFERENCES tasks (id),
  CONSTRAINT fk_progress_author FOREIGN KEY (author_id) REFERENCES users (id),
  CONSTRAINT ck_progress_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- @ 关系单独成表：被 @ 人可见性靠这张表反查，不能从正文里再解析一遍。
CREATE TABLE task_progress_mentions (
  progress_id BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (progress_id, user_id),
  KEY idx_mention_user (user_id, progress_id),
  CONSTRAINT fk_mention_progress FOREIGN KEY (progress_id) REFERENCES task_progress (id) ON DELETE CASCADE,
  CONSTRAINT fk_mention_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 深链 #progress-{id} 由这一列拼出，前端不再解析 dedup_key（TECHNICAL §4.7）。
ALTER TABLE notifications
  ADD COLUMN progress_id BIGINT UNSIGNED NULL AFTER task_id,
  ADD KEY idx_notify_progress (progress_id),
  ADD CONSTRAINT fk_notify_progress FOREIGN KEY (progress_id) REFERENCES task_progress (id);
