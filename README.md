# 会议任务督办系统

会议决议、项目任务、日常待办统一入池的督办台账：四字段强制、到期亮红灯、一键催办与验收闭环、
按人五列矩阵与管理层焦点看板、钉钉免登与指定领导督办日报。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Java 17 + Spring Boot 3.3.5（Web / JPA / Security / Validation / Actuator） |
| 数据库 | MySQL 8（utf8mb4），Flyway 迁移 |
| 前端 | Vue 3.5 + TypeScript + Vite + Pinia，自绘组件 |
| 调度 | Spring `@Scheduled`（单实例内网部署） |
| 外部通道 | 钉钉开放平台（免登 + 企业工作通知），未配置时自动降级 |

```
server/   Spring Boot 后端（Maven）
web/      Vue 前端（pnpm）
deploy/   Nginx / systemd / 环境变量示例
scripts/  smoke-api.sh 接口冒烟脚本
docs/     需求与设计文档（docs/teamflow/<需求夹>）
```

## 本地启动

### 1. 数据库

```bash
mysql -uroot -p -e "CREATE DATABASE supervise CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

Flyway 在应用启动时自动建表并写入初始 admin、默认报表设置；无需手工执行 SQL。
本地也可用 `docker compose up -d mysql`（见 `docker-compose.yml`）。

### 2. 后端

```bash
cd server
DB_NAME=supervise DB_USER=root DB_PASSWORD= ./mvnw spring-boot:run
# 或构建后运行
./mvnw -q -DskipTests package && java -jar target/supervise-1.0.0.jar
```

默认 `http://127.0.0.1:8080`，健康检查 `GET /actuator/health`。

可配置环境变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | 8080 | 后端端口 |
| `DB_HOST` / `DB_PORT` | 127.0.0.1 / 3306 | MySQL 地址 |
| `DB_NAME` | supervise | 数据库名（需预先创建） |
| `DB_USER` / `DB_PASSWORD` | root / 空 | 数据库账号 |
| `FRONTEND_BASE_URL` | http://localhost:5173 | 钉钉回调后回跳的前端地址 |
| `DINGTALK_CORP_ID` `DINGTALK_AGENT_ID` `DINGTALK_APP_KEY` `DINGTALK_APP_SECRET` | 空 | 钉钉四项凭证；缺任一项即降级 |
| `SUPERVISE_SCAN_STARTUP_CATCHUP_ENABLED` | true | 启动补跑当天扫描（仅当天） |

**时区**：应用固定 `Asia/Shanghai`（`ShanghaiClock` 是唯一“今天”来源），
JVM / MySQL 会话时区应保持一致；逾期判定不在前端推算。

### 3. 前端

```bash
cd web
pnpm install
pnpm dev        # http://localhost:5173，/api 代理到 8080
```

生产构建：`pnpm build`，产物 `web/dist/`。

## 初始账号与首登改密

| 账号 | 初始口令 | 说明 |
| --- | --- | --- |
| `admin` | `Admin@12345` | 角色 ADMIN，`must_change_password=1` |

首次登录会被强制跳转改密页；改密成功前除 `/api/auth/*` 外的接口返回 `409 PASSWORD_CHANGE_REQUIRED`。
**上线前必须改密**，并立即在「用户管理」里为真实人员分配角色（钉钉只提供身份，角色由管理员手工分配）。

## 钉钉配置（当前留空，系统可完整运行）

在 `deploy/.env.example` 中补齐后由 systemd 注入：

| 配置项 | 用途 | 缺失时行为 |
| --- | --- | --- |
| `DINGTALK_CORP_ID` | 企业 ID | 免登入口隐藏、推送标记「钉钉未配置」 |
| `DINGTALK_AGENT_ID` | 微应用 AgentId | 同上 |
| `DINGTALK_APP_KEY` / `DINGTALK_APP_SECRET` | 应用凭证（仅服务端使用） | 同上 |

缺少任一项时：启动只打印一条 WARN（只列缺失项名称，不打印值）；站内通知照常；
`notifications.dingtalk_status` 记为 `NOT_CONFIGURED`，不重试、不抛异常；密码登录与任务业务不受影响。
补齐后无需改代码，重启即生效；密钥不在页面回显、不允许页面编辑。

## 构建与测试

```bash
cd server && ./mvnw -q test                 # 单测 + MySQL 集成测试（默认库 supervise_test）
cd server && ./mvnw -q -DskipTests package  # 产出 target/supervise-1.0.0.jar
cd web && pnpm test                         # vitest
cd web && pnpm build                        # vue-tsc 类型检查 + 生产构建
bash scripts/smoke-api.sh                   # 接口冒烟（需后端已启动）
```

后端集成测试连接本机 MySQL 的 `supervise_test` 库（`createDatabaseIfNotExist=true`），
库不存在时自动创建；`server/src/test/resources/application-test.yml` 中可覆盖账号。

## 部署（单实例内网）

1. 构建 jar 与前端静态资源，上传到服务器 `/opt/supervise/`；
2. 按 `deploy/.env.example` 准备 `/etc/supervise/supervise.env`（权限 600）；
3. 按 `deploy/supervise.service.example` 安装 systemd 服务并 `systemctl enable --now supervise`；
4. 按 `deploy/nginx.conf.example` 配置 Nginx：静态资源 + `/api` 同源反代到 8080；
5. 数据库每日备份；`logs/` 交给 journald 或日志轮转。

前端开发代理 `web/vite.config.ts` 与生产 Nginx 都必须保持 `/api` 同源前缀，改一处要同步另一处。

## 目录约定

- 需求/设计/技术/验收文档：`docs/teamflow/<yyyyMMdd-rN-slug>/`
- 跨需求约定与待办：`docs/teamflow/memory.md`
- 运行日志：`logs/teamflow/<runId>/`（不入库，host 归档）
