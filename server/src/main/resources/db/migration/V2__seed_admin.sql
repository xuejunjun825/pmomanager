-- 初始管理员（TECHNICAL §3.4）：首次登录强制改密，避免默认口令长期存在。
-- 口令：Admin@12345（BCrypt cost 10，must_change_password=1）。
INSERT INTO users (username, password_hash, name, role, status, must_change_password)
VALUES ('admin', '$2a$10$J.jadwZEd6nV5yyUKWYiWe9sGMsAlj0OQt//TV9EQPdM1rY0GqmTO', '系统管理员', 'ADMIN', 'ACTIVE', 1);
