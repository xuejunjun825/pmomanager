# r2 来源配置 + 进度 @ · QA 复验报告（第 3 轮）

- 任务夹：`docs/teamflow/20261109-r2-source-config-progress/`（PRD 基线 r1；本报告为交付质量门唯一结论）
- 被测对象：`main` @ `b8c09a9` + 本轮修复（后端 4 类：`ErrorCode`/`GlobalExceptionHandler`/`DecisionConversionService`/`TaskService`；前端 3 处：`TaskDetailPage.vue`/`TaskTable.vue`/`DashboardPage.vue`；新增 `GlobalExceptionHandlerTest`、`web/tests/t12-source-form-display.spec.ts`）
- 结论：**可验收** —— 上轮 6 项缺陷中 D-01（P2）与 D-02/D-03/D-04/D-06（P3）均已关闭并逐条复跑通过；无未关闭的 P0/P1/P2。D-05（r1 既有）与 1 项可维护性观察保持 P3，不阻塞。

## 1. 范围与环境

| 项 | 内容 |
| --- | --- |
| 后端 | Spring Boot 3 / Java 17（本机 JDK 25.0.2 运行），MySQL 9.6，Flyway V1–V5 |
| 前端 | Vue 3 + Vite + Pinia，Node 22.22.0 / pnpm 10.30.0 |
| 实例 A（全新库） | `supervise_qa_r2` @ `127.0.0.1:18090`，空库迁移 V1–V5，夹具 13 用户（admin 当前口令 `Qa@2026pass`） |
| 实例 B（升级库） | `supervise_r2_upgrade` @ `18091`，由 r1 的 `supervise`（V3、7 条历史任务、5 用户）克隆后自动迁移至 V5，`source_types` 三条内置 |
| 真实浏览器 | **不可用**（宿主沙箱限制，非交付缺陷）→ 用 jsdom 挂载真实组件树做 DOM 断言；视觉/手感项列第 7 节 |
| 交付形态契约 | 本轮未注入该切片，N/A |
| 探针夹具 | `qa-r2-crosspage.py` 硬编码 `admin/Admin@12345`，升级库 admin 已按该口令对齐（复制夹具 bcrypt 哈希，`must_change_password=0`）；这是探针夹具对齐，不是交付缺陷 |

## 2. 复验执行（回归契约，全部实际执行）

| 批次 | 命令 | 结果 |
| --- | --- | --- |
| 后端单测 | `cd server && ./mvnw -q test` | exit 0，**198/198**（36 个 surefire 报告，0 fail/0 error） |
| 后端打包 | `cd server && ./mvnw -q -DskipTests package` | exit 0，`target/supervise-1.0.0.jar` 重新产出 |
| 前端单测 | `cd web && pnpm test` | exit 0，**93/93**（14 文件） |
| 前端构建 | `cd web && pnpm build` | exit 0，vue-tsc 0 error，vite built in 1.26s |
| 静态接口契约 | `python3 logs/teamflow/tf-mw96rkbc-ejjjuq/scripts/qa-r2-static-contract.py` | exit 0，**3 PASS**（53 个 api 调用名 / 71 个导出成员；55 条前端路径 / 55 条后端路由；来源形态不再按编码判断） |
| 外键清理顺序 | `bash logs/teamflow/tf-mw96rkbc-ejjjuq/scripts/check-fk-cleanup-order.sh` | exit 0，PASS（负向 1451 生效，正向顺序清理成功，事务回滚） |
| D-01 原断言 | `cd web && pnpm exec vitest run --config ../logs/teamflow/tf-mw96rkbc-ejjjuq/scripts/vitest.task-detail.config.mjs` | exit 0，**1/1**（修复前 exit 1：`task-detail-custom-source.spec.ts:94` 断言失败） |
| D-01 永久门禁 | `cd web && pnpm exec vitest run tests/t12-source-form-display.spec.ts` | exit 0，**2/2**（任务池会议标题 + 详情 `/meetings/9`） |
| 设置页来源卡 | `cd web && pnpm exec vitest run --config ../logs/teamflow/tf-mw96rkbc-ejjjuq/scripts/vitest.settings.config.mjs` | exit 0，**8/8** |
| 看板来源下拉 | `cd web && pnpm exec vitest run --config ../logs/teamflow/tf-mw96rkbc-ejjjuq/scripts/vitest.dashboard.config.mjs` | exit 0，**1/1**（选项顺序与「（已停用）」严格相等） |
| 修复点静态+运行时 | `python3 logs/teamflow/tf-mw96rkbc-ejjjuq/scripts/qa-r3-fixes.py` | exit 0，**12/12** |
| 全新库功能 | `BASE_URL=http://127.0.0.1:18090 DB_NAME=supervise_qa_r2 python3 .../qa-r2-functional.py fresh` | **PASS=81 FAIL=0** |
| AC-1 编辑路径 | 同上脚本 `crud` | **PASS=11 FAIL=0** |
| 升级兼容 | `BASE_URL=http://127.0.0.1:18091 DB_NAME=supervise_r2_upgrade python3 .../qa-r2-functional.py upgrade` | **10/10** |
| 跨页口径 | `BASE_URL=http://127.0.0.1:18091 python3 .../qa-r2-crosspage.py` | **11/11** |
| 端到端来源/进度 | `BASE_URL=http://127.0.0.1:18090 SMOKE_USER=admin SMOKE_PASSWORD='Qa@2026pass' python3 .../smoke-e2e-source-progress.py` | exit 0，`E2E OK`（新建→建任务→补进度→停用→删除 409→停用建任务 400→历史可筛） |
| 项目接口冒烟 | `BASE_URL=http://127.0.0.1:18090 SMOKE_USERNAME=admin SMOKE_PASSWORD='Qa@2026pass' SMOKE_OWNER_USERNAME=qaowner SMOKE_OWNER_PASSWORD='Admin@12345' SMOKE_MENTION_USERNAME=qamember SMOKE_MENTION_PASSWORD='Admin@12345' bash scripts/smoke-api.sh` | **PASS=28 FAIL=0 SKIP=0**（含登录/CSRF/越权/@/护栏） |
| 依赖与方法级单测 | `cd server && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test` | exit 0，1/1 |

## 3. 缺陷复验对照（本轮状态）

| 编号 | round-3 级 | 模块 | 原缺陷摘要 | 复验命令 | 复验结果 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| D-01 | P2 | 前端 · 来源形态一致性 | 自定义会议形态来源不渲染会议链接/标题 | D-01 原断言 + `t12` 门禁 + 静态契约 | 1/1、2/2、3 PASS | **关闭** |
| D-02 | P3 | 前端 · 看板筛选 | 看板来源下拉未按启用/sort/停用标注 | `vitest.dashboard.config.mjs` | 1/1（乱序 meta 仍输出规定顺序） | **关闭** |
| D-03 | P3 | 后端 · 权限单点 | `TaskService` 复制 canView 谓词 | `qa-r3-fixes.py` | 12/12（断言复用 `guard.canView` 且无 `TaskProgressDao`） | **关闭** |
| D-04 | P3 | 后端 · API 卫生 | `PATCH /api/source-types/MEETING` 兜底 500 | `qa-r3-fixes.py` + 单测 | 运行时 **405 `METHOD_NOT_ALLOWED`**；单测 exit 0 | **关闭** |
| D-05 | P3 | 后端 · 会话/CSRF（r1 既有） | 登录后紧跟写请求可能 403 | `git log -3 -- SecurityConfig.java` | 仅 r1 提交 `c1496d5` 触及，本轮未改 | 仍开（建议另开任务） |
| D-06 | P3 | 后端 · 来源单源 | 转任务硬编码 `'MEETING'` | `grep -rn "'MEETING'" server/src/main/java` + upgrade 探针转换链路 | 0 处；转换任务来源实测 `MEETING` | **关闭** |

**缺陷类命中数复核（与修复摘要逐项一致）**：`grep -rnE "\.type\s*===\s*['\"]MEETING['\"]" web/src` 由 2 处降为 **0 处**（exit 1）；`grep -rn "'MEETING'" server/src/main/java` 由 1 处降为 **0 处**（exit 1）。两处计数与修复摘要无分歧。

## 4. 缺陷（仅未关闭）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| D-05 | P3 | 后端 · 会话/CSRF（r1 既有） | 登录成功后立刻携带新的 XSRF-TOKEN 发写请求 | 登录后 token 稳定可用 | 登录后首个 GET 会清空 `XSRF-TOKEN` 并轮换会话，紧跟的写请求可能 403 | -（r1 行为，r2 未引入） | `git log -3 --format='%h %s' -- server/src/main/java/com/pmo/supervise/config/SecurityConfig.java` | 输出仅含 r1 提交 `c1496d5`（证明非本轮引入）；修复需单开任务并回归全站鉴权 |
| A-01 | P3 | 架构 · 前端来源选项映射 | 读 `web/src/components/task/TaskFilterBar.vue:58`、`web/src/pages/MatrixPage.vue:57`、`web/src/pages/DashboardPage.vue:76` | 「启用优先→sort→（已停用）」映射收敛为一处 helper | 三处复制同一段逻辑；D-02 正是漏改其中一处造成的漂移，后续新增页面仍有同类风险 | AC-5（可维护性，非功能失败） | - | - |

未发现 P0/P1/P2 缺陷。上述 2 项均为 P3（1 项 r1 既有、1 项可维护性观察），不阻塞验收。

## 5. 本轮新增覆盖（上轮遗漏面）

| 遗漏面 | 为何上轮未覆盖 | 本轮新增探针 | 结果 |
| --- | --- | --- | --- |
| 看板来源下拉的客户端渲染顺序 | 上轮只验了 `/api/meta` 的排序与三色，未挂载页面断言下拉文本 | `dashboard-source-options.spec.ts`（jsdom + AppSelect，meta 故意乱序） | 1/1 |
| 405 的真实 HTTP 语义 | 上轮只读代码，没有对不匹配方法发请求 | `qa-r3-fixes.py` 对 `PATCH /api/source-types/MEETING` 实测 | 405 `METHOD_NOT_ALLOWED` |
| 转任务链路的来源单源 | 上轮只做 grep，未跑转换 | upgrade 探针建会议+决议项→`/convert`→校验任务来源 | 来源 `MEETING`、会议标题存在 |
| D-01 的仓库内永久门禁 | 上轮仅一次性脚本复现 | `web/tests/t12-source-form-display.spec.ts` 两条断言 | 2/2 |

## 6. 架构与接口一致性（轻检）

- **架构蓝图**：本轮未注入蓝图切片；按代码事实核对，未见与 r2 技术方案的偏离。
- **单一来源成立**：`SourceTypeService.requireKnown`（:167）被 `MatrixService.java:150`、`TaskQueryBuilder.java:52` 复用；`requireEnabled`（:175）仅建任务 `TaskService.java:190` 使用；改名/停用/删除内部统一先 `requireKnown`。
- **SQL 单源**：`selectBase()` 仅 `DashboardService.java` 与 `TaskQueryDao.java` 使用（`TaskSql` 接口）；全仓无真实 `LocalDate.now()`/`CURDATE()`（仅注释），`today` 由 `ShanghaiClock` 逐请求下传。
- **权限单点**：`TaskAccessGuard.canView` 为唯一可见性谓词；`TaskService.java:166` 已复用，无重复实现。
- **分层无越界**：10 个 Controller 无一直接注入 `JdbcTemplate`；`source`/`progress` 两域各自 Controller→Service→Dao。
- **接口一致性（0a）**：静态核对 53 个前端 api 调用名全部在目标模块导出、55 条前端 `/api` 路径全部命中后端 55 条路由；后端全量编译 + 198 单测 + 真实 HTTP 探针验证参数形状；无「成员不存在 / 路径不存在」类 P1。
- **配色单源**：定制 hex 仅 `SourcePalette.java` 与 `web/src/styles/tokens.css` 持有，页面无写死色值。
- **观察项**：见 §4 A-01，来源选项投影逻辑三处复制，建议抽 `sourceOption(item)` helper；当前行为经 DOM 探针验证一致。

## 7. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法/工具 |
| --- | --- | --- |
| 设置页来源配置卡视觉 | 表格 844px、行高 56、色板选中环、骨架/空态/错误态 | Chrome 1280 宽截图对照 DESIGN §1.7 |
| 任务详情「进度与流转」 | 时间线锚点滚动、@ 高亮、200 上限禁用态、375 宽不溢出 | Chrome DevTools 设备模式 |
| 钉钉真实送达 | MENTION/催办未配置时跳过不阻塞；配置后真实到达 | 真实钉钉企业应用 + 抓包 |
| 钉钉扫码/免登 | 首登默认负责人、停用/伪造 state 拒绝 | 真实钉钉客户端 |
| 屏幕阅读器/焦点环 | @选择器 roving tabindex、色板方向键 | VoiceOver + 键盘 |

## 8. 结论

**可验收。** D-01（P2）及 D-02/D-03/D-04/D-06（P3）全部关闭，且均以原 Check command 或新增永久门禁复跑通过：后端 198 单测、前端 93 单测、构建、静态接口 3/3、功能探针 81/81、升级 10/10、CRUD 11/11、跨页 11/11、接口冒烟 28/28、D-01 门禁 2/2。未发现 P0/P1/P2 缺陷；仅 D-05（r1 既有会话/CSRF）与 A-01（前端选项映射重复）两项 P3 不阻塞。真实浏览器视觉与钉钉真实送达按第 7 节由人工补测。
