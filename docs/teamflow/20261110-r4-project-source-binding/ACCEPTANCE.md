# ACCEPTANCE · r4 项目来源绑定与项目配置

- 需求夹：`docs/teamflow/20261110-r4-project-source-binding/`（PRD / DESIGN / TECHNICAL / QA-REPORT 为本轮契约）
- 基线依赖：r3 `20261110-r3-*`（来源选项唯一投影、`SourceTag` 唯一渲染点）、r2 形态校验单点与错误码、r1 逾期与矩阵口径
- 验收人：验收负责人（acceptance 阶段）· 验收时间 2026-11-10
- 被测状态：分支 `main`，提交 `c56d87d` + 工作区未提交改动（见 §4，均为本需求交付内容，host 验收后统一提交）
- 结论：**✅ 通过**（无 P0/P1/P2 缺陷；3 条 P3 工艺观察列为遗留，不阻塞；真浏览器项为人工补测）

## 1. 验收方法与本次独立证据

不采信转述结论：测试、端到端脚本、库层探针与关键代码路径均由验收人本轮亲自执行/阅读。

| # | 证据 | 结果 |
| --- | --- | --- |
| V1 | `cd server && ./mvnw -q test` | exit 0；surefire 聚合 **42 个测试类 / 247 用例 / 0 fail 0 error 0 skip** |
| V2 | `cd web && pnpm test` | exit 0；**23 文件 / 174 用例全过** |
| V3 | `BASE_URL=http://127.0.0.1:18093 python3 logs/teamflow/tf-mwa3vl5p-mhaom9/scripts/qa-r4-t10-functional.py`（全新库 `supervise_qa_r4v`） | exit 0；**PASS=41 FAIL=0**（项目 CRUD、三形态、三层校验、删除/停用护栏、三处筛选、权限） |
| V4 | `BASE_URL=http://127.0.0.1:18093 DB_NAME=supervise_qa_r4v python3 .../qa-r4-ac6-audit.py` | exit 0；**PASS=51 FAIL=0**（AC-5 三处逐档精确合计、AC-6 零迁移、AC-8 审计、200 上限/排序） |
| V5 | `BASE_URL=http://127.0.0.1:18094 DB_NAME=supervise_qa_r4up python3 .../qa-r4-upgrade-verify.py`（V5 升级库克隆） | exit 0；**PASS=18 FAIL=0**（升级链） |
| V6 | 库层负向探针（`INSERT` 同时写 `source_note` 与 `source_project_id`） | 预期失败：`ERROR 3819 (HY000): Check constraint 'ck_tasks_source' is violated.` → 库层三态互斥真实生效 |
| V7 | 升级库直查（mysql 9.6.0） | `tasks 5` 行、`source_project_id` 全 NULL、`source_types` 6 行 form 仍为 `MEETING/NOTE`（内置编码 `PROJECT` 仍 NOTE）、`flyway V1–V6` 全 success |
| V8 | `audit_logs` 直查 | 含 `PROJECT_CREATED/DELETED`，`target_id` 为 `P%05d` 编码，detail 形如 `name=…; enabled=1; sort=99` |

说明：验收人另起 jar 复测 18093 失败（端口已被 QA 服务占用，日志 `Identify and stop the process that is listening on port 18093`），未产生残留进程，改用既有服务与既有库复核；此为环境状态，不影响证据有效性。

## 2. 逐条 AC 核对

| AC | 验收点 | 独立证据 | 结论 |
| --- | --- | --- | --- |
| **AC-1** 项目配置卡片 | 位置在「来源配置」之后（`SettingsPage.vue:450` 来源 → `:469` 项目）；仅 ADMIN 渲染且非管理员不发请求（`:81` + `:254`）；六列 = 名称/编码(只读)/状态/排序/引用任务数/操作；名称去空格后按 Unicode 码点 2–20（越界 422 落 `fieldErrors.name`）；重名（含已停用）409 `PROJECT_NAME_DUPLICATE`；编码 `P%05d` 服务端生成、编辑不可改；排序 0–99（UI 越界不请求 + Toast，`ProjectConfigTable.vue:134`）；上限 200：`ProjectService.java:78` 在 `FOR UPDATE` 同事务判定，第 201 个 409 `PROJECT_LIMIT_REACHED`，UI 达上限置灰「新建项目」并给出可见原因（`:186-196`）；启停/排序行内即时生效；5 个 `PROJECT_*` 审计动作带回前后值 | V3 全绿；V4 覆盖 200 上限、排序截断、审计；V8 审计直查；前端 11 用例（`web/tests/project-config.spec.ts`） | **PASS** |
| **AC-2** 建任务绑项目 | 形态扩为 `MEETING/PROJECT/NOTE` 三值，三态字段互斥（`ck_tasks_source`）；`validateSource` 单点判定且顺序为 ① 结构冲突 400 `PROJECT_SOURCE_CONFLICT` → ② 缺选 422 `fieldErrors.sourceProjectId`「项目来源必须选择项目」→ ③ 停用/已删 400 `PROJECT_DISABLED`/`PROJECT_INVALID`；`TaskService.java:201-260`；抽屉按形态只渲染对应字段，项目下拉必选、只列启用、编辑态只读回显；**同名不同义**：内置编码 `PROJECT` 仍按 `form=NOTE` 处理（`SourceForm.of` 仅 `valueOf`，全仓无按编码推断，无 `"PROJECT".equals`） | V3 全绿（含内置编码 `PROJECT` + `sourceNote` → 201、缺说明 → 422）；V6 库层兜底；`Server SourceFormProjectIT` 7 用例 | **PASS** |
| **AC-3** 升级兼容 | `V6__projects.sql` 只 `CREATE projects` + `ALTER tasks ADD source_project_id` + 换 CHECK，**无 UPDATE/DELETE**；V4 旧约束是被放宽约束的子集，历史行不会被拒；空库 V1–V6 全 success；V5 库克隆升级 0.109s，`tasks 5→5`、`task_progress 210→210`、`source_project_id` 全 NULL、内置「项目任务」仍 `form=NOTE`、迁移不写 `PROJECT_*` 审计；旧库接口总数不变、`meta/options.projects=[]`、`project=none` 不 500 | V5、V7 | **PASS** |
| **AC-4** 删除/停用护栏 | 被引用（含已取消/已验收）不可物理删除：`ProjectService.java:131-140` 先 `countTasks>0` → 409 `PROJECT_IN_USE` + `details.taskCount`，外键竞态捕获后重查仍 409 不外露 500；停用后新建/编辑下拉不出现（`includeDisabled:false`），构造请求提交停用项目 400；历史任务详情/列表仍显示项目名并带「（已停用）」；三处筛选仍可筛出历史任务；重新启用立即归位 | V3 全绿；前端 `project-options`/`t10-pages` 用例 | **PASS** |
| **AC-5** 三处筛选同源 | 单一谓词 `ProjectFilterSql.append`：`grep` 仅 `ProjectFilterSql` 本体、`MatrixService.java:97`、`TaskQueryBuilder.java:58` 三处引用，Dashboard 经 `TaskQueryBuilder.build`（`:126/:140`）复用，无第二套；单一前端投影 `web/src/utils/projectOptions.ts:projectOptions()/projectView()`，三处下拉均 `AppSearchSelect` 且参数一致（`allOption/includeDisabled/includeUnassigned` 全 true）；`none` 档 = `source_project_id IS NULL AND source_type IN (form='PROJECT')`；未知/非数字/`0` → 400 `PROJECT_INVALID` 三处一致，页面捕获后清空条件重取；**三处合计逐档精确相等**（与库内真实集合 SQL 比对） | V4 的 51 条断言（含逐档合计）；V3 未知 id 400 | **PASS** |
| **AC-6** 改形历史兼容 | `NOTE→PROJECT` 改形 200 后：`tasks` 行逐列不变、`task_events` 条数不变、历史任务 `projectId/projectName` 为空且保留 `note` 原文、显示「未指定项目」且该档可筛出；反向 `PROJECT→NOTE` 后已绑定任务仍按项目筛到、不再属「未指定」；MEETING 形态锁定（改形 403）；二次确认（`usageCount>0` 显示影响面警告）；`SourceTypeService.java:115-118` 前置条件改为 `!= MEETING`，修掉 `PROJECT→MEETING` 绕过未绑会议计数撞 CHECK 变 500 的漏洞（现为 409 `SOURCE_FORM_CONFLICT`） | V4（AC-6 零迁移断言）；`SourceFormProjectIT` 真实库回归 | **PASS** |
| **AC-7** 展示与跳转 | 详情项目名完整不截断（`TaskDetailPage.vue:345-352`，`.source__project` 仅 `overflow-wrap` 无 ellipsis），为 `RouterLink { path:'/tasks', query:{ project } }`，任务池 `useQuerySync` 读同名键；改形历史渲染不可点「未指定项目」；会议/说明来源不渲染项目元素；列表/卡片中性 `ProjectTag`（20px、`--c-ink-600` on `--c-line-soft` 对比度 6.98:1，AA 通过）；无项目不渲染空标签；停用后缀在链接内保留 | V2 前端用例；静态对比度断言 | **PASS**（文案观察见 P3-3） |
| **AC-8** 权限与审计 | 写操作仅 ADMIN：匿名 401、OWNER 403；`GET /api/projects` 仅 ADMIN（含 `usageCount`），全员读走 `meta/options.projects` 且只回 `id/name/enabled/sort`（`MetaService.java:77`，无 code/usageCount），与 PRD「接口只回传 id/name/enabled/sort，不含引用数与操作信息」一致；5 个 `PROJECT_*` 审计写入 `audit_logs`，`target_id=项目编码`，UPDATED 记录前后值 | V3/V4/V8；`ProjectController` 读权限代码 | **PASS** |
| **AC-9** 口径回归 | 后端 247 + 前端 174 全绿；任务池/矩阵/看板既有断言未改；项目**不是权限维度**（成员 qamember 任务池总数与 admin 同为 10）；`notify/`、`dingtalk/` 包无任何 project 引用（钉钉正文不含项目）；状态/留痕/逾期/KPI 口径由既有套件覆盖，未因项目筛选漂移 | V1、V2、V3 | **PASS** |

## 3. 架构与结构一致性（0a）

未注入 architecture blueprint 段，按 TECHNICAL 的模块划分与之对照：

| 检查项 | 证据 | 结论 |
| --- | --- | --- |
| 模块提取 | 新增 `project` 包：`ProjectDao` / `ProjectService` / `ProjectController` / `ProjectFilterSql` + 4 个 DTO，职责单一 | 与 TECHNICAL §3.1 一致 |
| 依赖方向 | `project` 只依赖 `common`/`ApiError`/`BusinessException`/`ErrorCode`/`auth.LoginAuditService` 与自身 dto，**不** import `task`/`dashboard`；`task`、`dashboard`、`meta` → `project` 单向，无环 | 结构健康 |
| 重复实现 | 服务端筛选谓词 1 份（`ProjectFilterSql`）、前端投影 1 份（`projectOptions.ts`）、项目标签渲染 1 份（`ProjectTag.vue`）、详情项目展示判定 1 处（`projectView`）；无第二套项目 API/排序/「未指定」判定 | 无 adapter drift |
| 既有结构 | `SourceTypeService` 仅放宽改形前置条件（未改流程与审计顺序）；`MetaService` 仅追加 `projects` 字段；`IntegrationTestSupport` 仅补清表顺序；未改动 `notify`/`dingtalk` | 未破坏既有结构 |

结论：无「偏离蓝图 / 重复实现 / 应抽未抽」，功能性全绿且结构一致。

## 4. 交付形态与工作区

- `git status --porcelain`：5 项，均为本需求源码/测试（`M web/src/components/settings/SourceTypeTable.vue` 改形富文案；`M web/tests/{matrix-dashboard-project,t10-pages,task-detail-project}.spec.ts` 补 API 替身；`?? web/tests/project-config.spec.ts`）+ 本需求文档夹。无 `node_modules/`、`target/`、`dist/`、`.idea/`、`__pycache__`、`.env`。
- `.gitignore`：`logs/`（line 2/34）与 `*.dump` 已忽略，**无 `*.sql`** 规则（V6 迁移必须入库）。
- 构建产物未入库；`logs/teamflow/tf-mwa3vl5p-mhaom9/` 为运行期暂存（host 归档后删除），非交付物。

## 5. 验收意见与遗留

**无 P0/P1/P2 缺陷。** 保留 3 条 P3（工艺/文案，均不阻塞交付，建议下一轮随改）：

1. **P3-1（建议优先）** `web/src/components/settings/ProjectConfigTable.vue:418` 只有 `overflow-x:auto`，无首列 `position: sticky`，与 `DESIGN.md:381`「≤768 横向滚动，首列 sticky」存在偏差 → 窄屏（钉钉 H5）滚动后名称列移出视野。不影响桌面与功能。
2. **P3-2** `web/src/pages/SettingsPage.vue:289` 页头仍写「报表生效范围、来源类型与钉钉部署状态」，未提「项目配置」，与页面现有五块内容不完全对应。
3. **P3-3** `TaskDetailPage.vue:370` 的 `v-else` 在项目型任务无说明时仍渲染「没有填写来源说明」，语义上项目型来源不需要说明，属误导性占位。

**已核定的非缺陷**（记录以免后续重复开单）：

- `GET /api/projects` 对非 ADMIN 返回 403 与 AC-8 不冲突：全员读入口是 `/api/meta/options`，且 `usageCount` 属操作信息不得下发给非管理员。
- 排序值越界：PRD 写「拒绝」，TECHNICAL 裁定「API 截断到 0/99（与既有来源配置 `clampSort` 一致）、UI 本地拒绝」，用户可见行为满足 AC，属文档层裁定而非实现缺陷。
- 「项目配置为第五块卡片」实为「来源配置」之后（第 4 块），与 PRD 原文「在来源配置之后新增」一致。
- 注释标签笔误：`TaskFormDrawer.vue:122` 注释写「AC-3」应为 AC-2/AC-4，仅注释文字。
- `TaskDetailPage.vue:346` 用 `detail.source?.projectId != null` 判链接而非 `projectView`，结果等价，属轻微偏离「唯一判定点」纪律，不构成缺陷。

**人工补测清单（环境限制，非交付缺陷）**：M1 真浏览器视觉两档分辨率、M2 ≤768/钉钉 H5 触控与 P3-1 吸附、M3 读屏（combobox `aria-activedescendant`、`aria-live` 计数、Toast 播报）、M4 中文 IME 组合输入、M5 动态视口与安全区、M6 200 项目实景滚动与搜索性能。沙箱内 Chromium/Edge 因 crashpad 权限被拒（`--no-sandbox` 无效），本轮以 jsdom + 静态断言替代，真浏览器项须在具备图形权限的环境补测。

## 6. 验收结论

九条 AC（AC-1…AC-9）逐条以可执行证据通过：后端 247 用例、前端 174 用例、端到端 41/51/18 断言全绿，库层三态互斥负向探针与升级链直查均确认；架构核对无重复实现、无反向依赖、无应抽未抽；工作区无构建产物或临时污染。3 条 P3 与 6 项人工补测为遗留事项，不构成阻塞。

验收结论：✅ 通过
