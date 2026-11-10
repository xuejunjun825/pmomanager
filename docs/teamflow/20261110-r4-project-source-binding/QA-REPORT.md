# QA-REPORT · r4 项目来源绑定与项目配置

- 需求夹：`docs/teamflow/20261110-r4-project-source-binding/`（PRD / DESIGN / TECHNICAL 为本轮契约）
- 基线依赖：r3 `20261110-r3-*`（sourceOptions 唯一投影、SourceTag 唯一渲染点）；r2 形态校验与错误码；r1 逾期与矩阵口径
- 被测提交：`c56d87d` + 工作区未提交改动（见 §5）
- 结论：**可验收**（未发现 P0/P1/P2；3 条 P3 观察与真浏览器项见 §6/§7）

## 1. 范围、环境与方法

| 项 | 内容 |
| --- | --- |
| 被测面 | 项目主数据 CRUD、来源三形态（MEETING/PROJECT/NOTE）、建任务绑项目、任务池/矩阵/看板同源项目筛选、详情/列表项目展示与跳转、权限与审计、升级兼容、口径回归 |
| AC 覆盖 | AC-1…AC-9 全部；AC-10…AC-21 为既有能力，按回归对待 |
| 后端环境 | macOS；Spring Boot jar `server/target/supervise-1.0.0.jar`（13:36 构建）；MySQL 127.0.0.1:3306 (root 无密码) |
| 全新库 | `supervise_qa_r4v`，空库启动 → Flyway V1–V6 全部 success；服务 `http://127.0.0.1:18093` |
| 升级库 | `supervise_qa_r2`(V5, 5 tasks / 210 progress) 克隆为 `supervise_qa_r4up`；新 jar 启动 → `Migrating 6 - projects`，0.109s；服务 `http://127.0.0.1:18094` |
| 前端 | `web/`（Vue3 + vitest/jsdom），Node 由项目 `pnpm` 提供 |
| 方法 | ①真实 HTTP 端到端（两个库、两个端口）②库层 SQL 断言与约束负向探针 ③静态接口一致性 grep（0a）④jsdom 组件/页面测试 ⑤对比度等脚本化可访问性断言 |
| 浏览器 | 沙箱内 Chromium/Edge 均因 crashpad 需 PROCESS_ALL_ACCESS 被拒（`--no-sandbox` 无效），**环境限制，非交付缺陷**；UI 改用 jsdom + 静态断言，真浏览器项入 §7 |
| 未注入架构蓝本 | 平台未注入 blueprint 段，架构核对按 TECHNICAL 的模块划分与之对照（§4） |

## 2. 执行证据（每条命令独立执行）

| # | 命令 | 结果 |
| --- | --- | --- |
| E1 | `cd server && ./mvnw -q test` | exit 0；42 个测试类 / 247 用例 / 0 fail 0 error 0 skip（聚合 `server/target/surefire-reports`） |
| E2 | `cd web && pnpm test` | exit 0；23 文件 / 174 用例全过 |
| E3 | `cd web && pnpm build`（vue-tsc --noEmit + vite build） | exit 0 |
| E4 | `BASE_URL=http://127.0.0.1:18093 python3 logs/teamflow/tf-mwa3vl5p-mhaom9/scripts/qa-r4-t10-functional.py` | exit 0；**PASS=41 FAIL=0**（项目 CRUD/三态/护栏/筛选/权限） |
| E5 | `BASE_URL=http://127.0.0.1:18093 DB_NAME=supervise_qa_r4v python3 logs/teamflow/tf-mwa3vl5p-mhaom9/scripts/qa-r4-ac6-audit.py` | exit 0；**PASS=51 FAIL=0**（AC-6 零迁移、AC-5 三处精确合计、AC-8 审计、上限与排序） |
| E6 | `BASE_URL=http://127.0.0.1:18094 DB_NAME=supervise_qa_r4up python3 logs/teamflow/tf-mwa3vl5p-mhaom9/scripts/qa-r4-upgrade-verify.py` | exit 0；**PASS=18 FAIL=0**（升级链） |
| E7 | `cd web && pnpm exec vitest run tests/project-options.spec.ts tests/project-config.spec.ts tests/task-form-project.spec.ts tests/task-pool-project.spec.ts tests/task-detail-project.spec.ts tests/matrix-dashboard-project.spec.ts tests/app-search-select.spec.ts tests/source-form-three.spec.ts` | exit 0；8 文件 / **71 用例全过** |
| E8 | `mysql ... supervise_qa_r4v -e "INSERT ... (source_note, source_project_id 同时非空)"` | 预期失败：`ERROR 3819 (HY000): Check constraint 'ck_tasks_source' is violated.`（库层兜底生效） |
| E9 | 迁移/种子前后计数（SQL，升级库） | `tasks 5→5`、`task_progress 210→210`、`source_project_id` 全 NULL、`source_types` 6 行形态不变、`projects` 0 行 |

## 3. 用例与结果（按 AC 归组）

| AC | 用例（选摘） | 结果 |
| --- | --- | --- |
| AC-1 | 名称 2–20 字（码点，1 字 / 21 字 → 422 落 `name`）；重名（含已停用）→ 409 `PROJECT_NAME_DUPLICATE`；编码 `P%05d` 系统生成且编辑不变；`sort` 缺省 max+1、-5→0、120→99；第 201 个 → 409 `PROJECT_LIMIT_REACHED`；行内启停 200；被引用删除 409 `PROJECT_IN_USE` 且 `details.taskCount≥1`；未被引用删除 204 | PASS |
| AC-2 | 建 PROJECT 形态任务缺 `sourceProjectId` → 422 落 `sourceProjectId`；同时带会议/说明 → 400 `PROJECT_SOURCE_CONFLICT`；停用项目 → 400 `PROJECT_DISABLED`；合法 → 201 且详情回读 `form=PROJECT, projectId, projectName`；**库层三态互斥**（note+project 同时非空被 `ck_tasks_source` 拒绝，E8）；**同名不同义**：内置 code=`PROJECT` 仍按 NOTE 形态，带 `sourceNote` → 201、缺说明 → 422 落 `sourceNote` | PASS |
| AC-3 | 空库 V1–V6 全 success；V5 克隆升级 0.109s、5 条迁移校验通过；tasks/progress 行数不变、`source_project_id` 全 NULL、内置「项目任务」仍 `form=NOTE`、迁移不写 `PROJECT_*` 审计；旧库接口总数与升级前一致、历史任务无 `projectId`、说明原文保留、`meta/options.projects=[]`、`project=none` 不 500（E6） | PASS |
| AC-4 | 被引用项目删除 409 + usageCount；停用后新建任务 400 `PROJECT_DISABLED`；停用项目仍可筛选（200，历史照常返回）；详情返回 `projectEnabled=false` → 前端加「（已停用）」；建任务下拉 `includeDisabled:false`（停用项不可选） | PASS |
| AC-5 | 任务池 `total` = 矩阵行合计 = 看板 KPI（项目档、未指定档各 1 组）；两档合计与库内真实集合**逐档精确相等**（SQL 比对）；`project=abc` / `0` / 未知 id 三处均 400 `PROJECT_INVALID`；页面捕获 `PROJECT_INVALID` 清空条件重取（jsdom 用例）；三处均为 `AppSearchSelect`、同一 `projectOptions` 参数（allOption/includeDisabled/includeUnassigned 全 true） | PASS |
| AC-6 | NOTE→PROJECT 改形 200 后任务详情 `form=PROJECT`、`projectId/projectName` 仍空、`note` 原文返回；`tasks` 行逐列不变、`task_events` 条数不变；`project=none` 命中该历史任务且不误收 NOTE 形态任务；PROJECT→NOTE 反向改形后绑定任务仍返回 `projectId` 并按项目筛到、不再属于「未指定项目」；内置 MEETING 改形 403；内置 PROJECT（有 3 条未绑会议任务）改会议型 409 `SOURCE_FORM_CONFLICT`，同形态保存 200 | PASS |
| AC-7 | `projectView()` 为唯一渲染判定（TaskTable / TaskDetailPage / DashboardPage / ProjectTag）；详情项目名为完整链接 `to={path:'/tasks', query:{project:id}}`，停用后缀仍在链接内；改形历史渲染不可点「未指定项目」；会议/说明来源不渲染项目元素；列表标签中性（`ProjectTag` 20px、`--c-ink-600` on `--c-line-soft` 对比度 **6.98:1**，AA 通过） | PASS |
| AC-8 | 匿名写项目 401、OWNER 写/读 `/api/projects` 403；`GET /api/projects` 含 `usageCount`；全员 `meta/options.projects` 仅 `id/name/enabled/sort`（无 usageCount）；`audit_logs` 含 `PROJECT_CREATED/UPDATED/ENABLED/DISABLED/DELETED`，`target_id=项目编码`，UPDATED 记录前后值 | PASS |
| AC-9 | server 247 + web 174 全绿；任务池/矩阵/看板既有断言不变；成员（qamember）任务池总数与 admin 同为 10（项目不是权限维度）；`notify/`、`dingtalk/` 包无任何 project 引用（钉钉正文不含项目）；状态、留痕、逾期、KPI 口径由既有套件与 E4 覆盖 | PASS |

## 4. 架构与接口一致性核对（0a，静态）

| 检查 | 证据 | 结论 |
| --- | --- | --- |
| 单一筛选谓词 | `ProjectFilterSql.append` 被 `TaskQueryBuilder`（任务池 + 看板 KPI/焦点）与 `MatrixService` 共用，Dashboard 经 `TaskQueryBuilder.build` 复用；`grep -rn "ProjectFilterSql" server/src/main/java` 仅 4 处引用 | 无重复实现 |
| 单一前端投影 | `projectOptions()` / `projectView()` 仅 `web/src/utils/projectOptions.ts`；`（已停用）` 在项目域只出现在该文件；`ProjectTag` 为唯一展示点 | 与 r3 纪律一致 |
| 模块依赖 | `project` 包不 import `task`（只经 SQL 读 usageCount），`task`/`dashboard` → `project` 单向，无环 | 结构健康 |
| API 契约 | `projectsApi.list/create/update/setEnabled/remove` ↔ `@GetMapping/@PostMapping/@PutMapping("/{id}")/@PatchMapping("/{id}/status")/@DeleteMapping("/{id}")`；`ProjectPayload{name,sort?,enabled?}` ↔ `ProjectRequest{name,sort,enabled}`；`ProjectRow{id,code,name,sort,enabled,usageCount}` ↔ `ProjectView` 六字段一致 | 无错位 |
| 组件契约 | `ProjectConfigTable` props `items/loading/error`、emit `refresh` 与 SettingsPage 用法一致；`ProjectFormModal` props `open/row/nextSort`、emit `saved/close` 与表格用法一致 | 无错位 |
| 筛选参数 | 三处页面均发 `project`；服务端 `TaskFilter.project`、`MatrixFilter.project`、`@ModelAttribute` 绑定一致；`UNASSIGNED_VALUE='none'` 三处同一常量 | 无错位 |
| 预设筛选 | 详情 `RouterLink` query 键 `project`，任务池 `useQuerySync` 读取同名键 | 无错位 |
| 无死代码/重复适配 | 未发现第二套项目 API、第二套项目排序或第二套「未指定」判定 | — |

## 5. 交付形态与提交面（0c）

- `git status --porcelain`：5 项，均为本次交付源码/测试，**无** `node_modules/`、`target/`、`dist/`、`.idea/`、`__pycache__`、`.env`。
  - `M web/src/components/settings/SourceTypeTable.vue`（补 T07 越界项：改形富文案 toast）
  - `M web/tests/{matrix-dashboard-project,t10-pages,task-detail-project}.spec.ts`（补 API 替身，消除 jsdom 真实 XHR 噪声）
  - `?? web/tests/project-config.spec.ts`（T10 补齐的 11 用例）
- `.gitignore`：`logs/` 双重忽略，`*.sql` 未被忽略（V6 迁移必须入库），`*.dump` 已忽略。
- 说明：平台状态行称「工作区干净」与实际不符；上述改动均属本需求交付内容，由 host 在验收后统一提交，**不构成缺陷**。
- 构建产物未入库；`logs/teamflow/…` 为运行期暂存，未提交、未清理。

## 6. 缺陷表

**未发现 P0/P1/P2 缺陷。**

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P3-1 | P3 | 系统设置·项目配置（响应式） | 窄屏（≤768）打开 `/settings#projects`，横向滚动项目表 | 首列「名称」吸附（DESIGN §1.6/§5：`overflow-x:auto` + 首列 sticky） | 仅有 `overflow-x:auto`（`ProjectConfigTable.vue:418`），全文件无 `position: sticky`、无 `max-width:768px` 媒体查询，首列随滚动移出 | AC-1（工艺观察） | `grep -c "position: sticky" web/src/components/settings/ProjectConfigTable.vue` 当前为 0 | - |
| P3-2 | P3 | 系统设置·页头文案 | 打开 `/settings` | 页头说明覆盖页面现有五块卡片（含新增项目配置） | 仍为「报表生效范围、来源类型与钉钉部署状态」，未提项目配置（`SettingsPage.vue:289`） | AC-1（工艺观察） | `grep -n "报表生效范围、来源类型与钉钉部署状态" web/src/pages/SettingsPage.vue` 仍命中 | - |
| P3-3 | P3 | 任务详情·来源区文案 | 打开一条「需绑定项目」且已选项目、无来源说明的任务详情 | 项目已展示时，说明区不应出现误导性占位 | 渲染「没有填写来源说明」（`TaskDetailPage.vue:370` 的 `v-else` 分支），项目型任务语义上不需要说明 | AC-7（工艺观察） | `grep -n "没有填写来源说明" web/src/pages/TaskDetailPage.vue` 命中且无 `form==='PROJECT'` 分支 | - |

## 7. 人工补测清单（环境限制，非交付缺陷）

| # | 项目 | 验收标准 | 方法 / 工具 |
| --- | --- | --- | --- |
| M1 | 真浏览器视觉 | 项目配置表六列 728 不溢出；改形弹窗 560；下拉浮层不遮挡/不越界；hover/focus/disabled 视觉符合 DESIGN | Chrome/Edge 1280 与 1024 两档，逐屏截图 |
| M2 | 窄屏与触控（≤768 钉钉 H5） | 项目表横向滚动且首列吸附（见 P3-1）；筛选条每行 2 控件；抽屉「来源项目」40 高、浮层 50vh；详情项目链接触控区 44 | DevTools 设备模拟 + 真机钉钉容器 |
| M3 | 读屏 | `AppSearchSelect` combobox `aria-activedescendant` 正确播报；底栏计数 `aria-live`；改形复选框必勾提示可读；Toast 播报 | VoiceOver / NVDA |
| M4 | 中文 IME | 搜索框组合输入期间不误触发选择/提交（Esc、Enter、方向键） | Windows 微软拼音 + macOS 拼音，手工用例 |
| M5 | 动态视口/安全区 | 钉钉 H5 内 100dvh、底部安全区不遮挡保存按钮 | 真机 iPhone/Android |
| M6 | 200 项目实景 | 下拉 200 项滚动流畅、搜索过滤无卡顿 | 造 200 项目后真浏览器观测（本轮仅以 SQL + API 验证上限与 409） |

## 8. 结论

- 功能、口径、权限、审计、升级兼容与三处筛选一致性均以**可执行证据**通过（E1–E9），前端 71 条项目相关用例与后端 247 条套件全绿。
- 架构与接口一致性核对未发现重复实现、抽象缺失或调用错位；项目筛选与展示均为单点实现，与 r3 基线纪律一致。
- 无 P0/P1/P2 缺陷，**可验收**。3 条 P3 为工艺/文案观察，不阻塞；建议随下次改动处理（P3-1 窄屏吸附优先）。

<!-- 复跑入口：logs/teamflow/tf-mwa3vl5p-mhaom9/scripts/{qa-r4-t10-functional.py, qa-r4-ac6-audit.py, qa-r4-upgrade-verify.py} -->
