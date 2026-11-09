-- 系统设置默认值（TECHNICAL §3.4）
-- 收件人默认为空数组：空名单即触发“未配置收件人”提示，而不是静默发送。
INSERT INTO settings (k, v) VALUES
  ('report.daily.enabled',  'true'),
  ('report.daily.time',     '18:00'),
  ('report.weekly.enabled', 'true'),
  ('report.weekly.time',    '09:00'),
  ('report.recipients',     '[]');
