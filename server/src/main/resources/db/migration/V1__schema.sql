-- 会议任务督办系统 · 初始结构（TECHNICAL §3.2 逐字口径）
-- MySQL 8.0+ / InnoDB / utf8mb4_0900_ai_ci
-- 顺序：users → meetings → decision_items → tasks → task_events → notifications
--       → urge_records → job_runs → settings → audit_logs

CREATE TABLE users (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username              VARCHAR(64)     NULL,
  password_hash         VARCHAR(100)    NULL,
  dingtalk_union_id     VARCHAR(64)     NULL,
  dingtalk_user_id      VARCHAR(64)     NULL,
  name                  VARCHAR(64)     NOT NULL,
  avatar_url            VARCHAR(500)    NULL,
  department            VARCHAR(100)    NULL,
  role                  VARCHAR(16)     NOT NULL DEFAULT 'OWNER',
  status                VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
  must_change_password  TINYINT(1)      NOT NULL DEFAULT 0,
  last_login_at         DATETIME        NULL,
  created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_username (username),
  UNIQUE KEY uk_users_union (dingtalk_union_id),
  CONSTRAINT ck_users_role   CHECK (role IN ('ADMIN', 'OWNER', 'EXEC')),
  CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_users_identity CHECK (username IS NOT NULL OR dingtalk_union_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE meetings (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title         VARCHAR(100)    NOT NULL,
  meeting_date  DATE            NOT NULL,
  host          VARCHAR(64)     NOT NULL,
  attendees     VARCHAR(500)    NULL,
  note          VARCHAR(500)    NULL,
  archived      TINYINT(1)      NOT NULL DEFAULT 0,
  created_by    BIGINT UNSIGNED NOT NULL,
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_meetings_date (meeting_date),
  CONSTRAINT fk_meetings_creator FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT ck_meetings_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE decision_items (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  meeting_id     BIGINT UNSIGNED NOT NULL,
  description    VARCHAR(100)    NOT NULL,
  owner_id       BIGINT UNSIGNED NOT NULL,
  due_date       DATE            NOT NULL,
  delivery_note  VARCHAR(500)    NULL,
  sort_no        INT             NOT NULL DEFAULT 0,
  created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_di_meeting (meeting_id, sort_no),
  CONSTRAINT fk_di_meeting FOREIGN KEY (meeting_id) REFERENCES meetings (id),
  CONSTRAINT fk_di_owner   FOREIGN KEY (owner_id)   REFERENCES users (id),
  CONSTRAINT ck_di_description CHECK (CHAR_LENGTH(description) BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tasks (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title                 VARCHAR(100)    NOT NULL,
  owner_id              BIGINT UNSIGNED NOT NULL,
  due_date              DATE            NOT NULL,
  source_type           VARCHAR(16)     NOT NULL,          -- MEETING | PROJECT | DAILY
  source_meeting_id     BIGINT UNSIGNED NULL,
  source_decision_item_id BIGINT UNSIGNED NULL,
  source_note           VARCHAR(200)    NULL,
  status                VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
  dispatcher_id         BIGINT UNSIGNED NOT NULL,
  priority              VARCHAR(8)      NOT NULL DEFAULT 'MEDIUM',
  completion_note       VARCHAR(500)    NULL,
  completion_url        VARCHAR(500)    NULL,
  submitted_at          DATETIME        NULL,
  completed_at          DATETIME        NULL,
  cancelled_at          DATETIME        NULL,
  version               INT             NOT NULL DEFAULT 0,
  created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tasks_decision (source_decision_item_id),
  KEY idx_tasks_owner_status_due (owner_id, status, due_date),
  KEY idx_tasks_status_due (status, due_date),
  KEY idx_tasks_meeting (source_meeting_id),
  CONSTRAINT fk_tasks_owner      FOREIGN KEY (owner_id)      REFERENCES users (id),
  CONSTRAINT fk_tasks_dispatcher FOREIGN KEY (dispatcher_id) REFERENCES users (id),
  CONSTRAINT fk_tasks_meeting    FOREIGN KEY (source_meeting_id) REFERENCES meetings (id),
  CONSTRAINT fk_tasks_decision   FOREIGN KEY (source_decision_item_id) REFERENCES decision_items (id),
  CONSTRAINT ck_tasks_source CHECK (
    (source_type = 'MEETING' AND source_meeting_id IS NOT NULL AND source_decision_item_id IS NOT NULL AND source_note IS NULL)
    OR (source_type IN ('PROJECT','DAILY') AND source_note IS NOT NULL AND source_meeting_id IS NULL AND source_decision_item_id IS NULL)
  ),
  CONSTRAINT ck_tasks_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 100),
  CONSTRAINT ck_tasks_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'PENDING_ACCEPT', 'DONE', 'CANCELLED')),
  CONSTRAINT ck_tasks_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_events (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id      BIGINT UNSIGNED NOT NULL,
  action       VARCHAR(24)     NOT NULL,
  actor_id     BIGINT UNSIGNED NOT NULL,
  from_status  VARCHAR(16)     NOT NULL,
  to_status    VARCHAR(16)     NOT NULL,
  reason       VARCHAR(500)    NULL,
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_event_task (task_id, created_at),
  CONSTRAINT fk_event_task  FOREIGN KEY (task_id)  REFERENCES tasks (id),
  CONSTRAINT fk_event_actor FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notifications (
  id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id                 BIGINT UNSIGNED NOT NULL,
  task_id                 BIGINT UNSIGNED NULL,
  type                    VARCHAR(24)     NOT NULL,
  notify_date             DATE            NOT NULL,
  dedup_key               VARCHAR(128)    NOT NULL,
  title                   VARCHAR(120)    NOT NULL,
  body                    VARCHAR(1000)   NOT NULL,
  dingtalk_status         VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
  dingtalk_attempts       TINYINT         NOT NULL DEFAULT 0,
  dingtalk_next_retry_at  DATETIME        NULL,
  dingtalk_last_error     VARCHAR(500)    NULL,
  read_at                 DATETIME        NULL,
  created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notify_dedup (dedup_key),
  KEY idx_notify_user (user_id, created_at),
  KEY idx_notify_retry (dingtalk_status, dingtalk_next_retry_at),
  CONSTRAINT fk_notify_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_notify_task FOREIGN KEY (task_id) REFERENCES tasks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE urge_records (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id         BIGINT UNSIGNED NOT NULL,
  actor_id        BIGINT UNSIGNED NOT NULL,
  dingtalk_status VARCHAR(24)     NOT NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_urge_task_actor (task_id, actor_id, created_at),
  CONSTRAINT fk_urge_task  FOREIGN KEY (task_id)  REFERENCES tasks (id),
  CONSTRAINT fk_urge_actor FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE job_runs (
  job_key     VARCHAR(32) NOT NULL,
  run_date    DATE        NOT NULL,
  status      VARCHAR(16) NOT NULL,
  detail      VARCHAR(500) NULL,
  started_at  DATETIME    NOT NULL,
  finished_at DATETIME    NULL,
  PRIMARY KEY (job_key, run_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE settings (
  k           VARCHAR(64)     NOT NULL,
  v           VARCHAR(2000)   NOT NULL,
  updated_by  BIGINT UNSIGNED NULL,
  updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (k),
  CONSTRAINT fk_settings_user FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_logs (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_id     BIGINT UNSIGNED NULL,
  action       VARCHAR(48)     NOT NULL,
  target_type  VARCHAR(32)     NULL,
  target_id    VARCHAR(64)     NULL,
  detail       VARCHAR(1000)   NULL,
  ip           VARCHAR(45)     NULL,
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_audit_action (action, created_at),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
