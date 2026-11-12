# r7 主题化甘特 × 会议登记 × 任务闭环 · QA 报告

- 任务夹：`docs/teamflow/20261112-r7-themed-gantt-task-loop/`
- 运行：`tf-mwd8png8-wnibn2` · 分支 `main`（未提交改动 92 项，任务夹为未跟踪）
- 结论：**可验收**。未发现 P0/P1/P2；3 条 P3 观察（1 条真实布局错位 + 2 条展示重复），不阻塞验收，建议下一轮顺手修复 D-01。

## 1. 范围与环境

| 项 | 值 |
|---|---|
| 本轮范围 | 主题主数据（V7）、任务挂主题/计划开始、会议批量建会与任务进度区、任务五步闭环条、角色化会议链接、甘特聚合与前端页、300 截断、风险/逾期同源、任务池主题筛选 |
| 不在范围 | AC-15~AC-21（既有看板/矩阵/钉钉能力），仅做回归 |
| 后端 | `http://127.0.0.1:18081`，jar 构建时间晚于全部 `server/src/main` 源码；Flyway V7 已应用 |
| 前端 | Vite `http://localhost:5174`（当前源码，非旧构建） |
| 数据库 | MySQL `127.0.0.1:3306` / `supervise`（root 无密码）；测试库 `supervise_test` |
| 浏览器 | Playwright Chromium headless 可真实启动（`NODE_PATH=/Users/xuejunjun/.npm-global/lib/node_modules`），本报告 UI 断言均为真实浏览器执行，非 DOM stub |
| 数据基线 | 25 条任务、3 个主题、admin(ADMIN)/zhangwei·lina·chenqiang(OWNER)/wangjianguo(EXEC) |

## 2. 验证命令与结果

| 编号 | 内容 | 命令 | 结果 |
|---|---|---|---|
| S1 | 后端全量单测/集成 | `cd server && ./mvnw -q test` | exit 0，305/305，failures=0 errors=0 |
| S2 | 前端全量单测 | `cd web && pnpm test` | exit 0，38 文件 300/300 |
| S3 | 类型与构建 | `cd web && pnpm typecheck` / `pnpm build` | exit 0；build 1.45s，GanttPage 22.95kB |
| S4 | API 契约回环（独立重跑） | `python3 logs/teamflow/tf-mwd8png8-wnibn2/scripts/r7-api-loop.py` | 72/72 |
| S5 | 边界/同源独立探针 | `python3 logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-r7-boundary.py` | 61/61 |
| S6 | 会议登记 UI 探针 | `NODE_PATH=… node logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-r7-meeting-ui.cjs --base http://localhost:5174` | 13/13 |
| S7 | 角色探针 | `NODE_PATH=… node logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-r7-roles.cjs --base http://localhost:5174` | 7/7 |
| S8 | 甘特时序/折叠/点击 | `NODE_PATH=… node logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-gantt-timing.cjs --base http://localhost:5174` | 15/15 |
| S9 | 300 截断 UI | `NODE_PATH=… node logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-r7-truncate-ui.cjs --base http://localhost:5174` | 5/5 |
| S10 | 真机 12 步闭环（独立重跑） | `NODE_PATH=… node logs/teamflow/tf-mwd8png8-wnibn2/scripts/r7-real-machine.cjs --base http://localhost:5174` | 12/12，badWrites=[] |
| S11 | 主题表列几何 | `NODE_PATH=… node logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-settings-layout.cjs --base http://localhost:5174` | **exit 1**，5 列错位（D-01） |

关键实测值：看板/甘特/任务池逾期均 3、风险均 3；待验收满 3 天 atRisk=true 且 isOverdue=false、2 天 false；窗口外风险任务仍返回且 pinnedStart=true；甘特日档 14 天、切档/翻屏各 1 请求、风险 chip 0 请求、首屏 102ms；305 条时提示「窗口内共 330 条，当前显示 300 条」且只渲染 300 条；批量建会第 2 行缺截止 → `items[2].dueDate` 且整批不落库；EXEC 甘特 200、`/meetings/{id}/tasks` 403；OWNER 任务详情无会议链接并提示「仅管理员可查看会议」。

## 3. 验收项覆盖

| AC | 结论 | 证据 |
|---|---|---|
| AC-1 会议人员可搜索、主持人自动进参会人 | 通过 | S6：候选出现、chip 自动加入、host 原文保留 |
| AC-2 建会 0..N 决议同事务、错误定位第 K 行 | 通过 | S5 批量建会 `items[2].dueDate` + 会议数不变；S4 |
| AC-3 Enter 连续录入、失败回焦不清空 | 通过 | S6：保存后开新行且焦点落 `new:description`；缺负责人时不落库、内容保留、焦点回 `new:ownerId` |
| AC-4 会议任务进度四卡单一计数 | 通过 | S10：progressCards=4、progressText「已转 0/2·未完成 0·逾期 0·已完成 0」；MeetingTasksApiTest 端到端 closed=true |
| AC-5 任务→会议链接按角色 | 通过 | S7：OWNER 无链接且有说明；EXEC 会议任务接口 403 |
| AC-6 五步闭环条 + 下一步提示 | 通过 | S10 statusFlowSteps=5；截图 05 显示「下一步：负责人 张伟「开始处理」」 |
| AC-7 状态/逾期/风险同源 | 通过 | S5：SQL=看板=甘特=任务池 3=3=3；3 天/2 天边界；前端唯一日期比较为表单提示（TaskFormDrawer.vue:251），不参与逾期判定 |
| AC-8 主题主数据（名称/上限/停用/引用） | 通过 | S5：trim、1 字/21 字 422、停用重名 409、被引用删除 409；上限 100 由 `ThemeServiceTest:143` 覆盖 |
| AC-9 任务挂主题三入口、计划开始、422 回填 | 通过 | S4/S5：建任务带主题三色回读、停用/未知 422 themeId、planned>due 422、themeId=0 清空、clearPlannedStart 清空；组件测试 task-form-theme |
| AC-10 甘特仅 ADMIN/EXEC、主题+项目两级、缩放翻屏、点击详情 | 通过 | S7 + S8：OWNER 落 /403；EXEC 有条；日/周/月切换；点击条进入 /tasks/10 |
| AC-11 摘要即筛、逾期不裁剪、折叠会话保持 | 通过 | S5 窗口外风险 pin；S8 折叠 1→1 路由往返保持、展开后 3 条仍在；风险 chip 本地 0 请求 |
| AC-12 300 截断、空窗、单请求、1.5s | 通过 | S9 5/5；S8 单请求与 102ms；空态由 GanttServiceTest 覆盖 |
| AC-13 任务池主题筛选（含未归）+ 行内改主题 | 通过 | S5：theme=none 18 == 甘特未归 18、theme=abc 400；S4 行内 PATCH 局部更新 |
| AC-14 回归与真机 | 通过 | S1/S2/S3/S10 |

## 4. 架构与接口一致性核查

- **蓝图模块落地**：`ThemeService/ThemeController/ThemeDao`、`TaskRiskSpec/ThemeFilterSql`、`GanttService/GanttDao/GanttController`、`TaskStatusFlow/UserMultiSelect/ThemeTag/GanttChart/useGanttView/useGanttWindow` 均按蓝图路径存在（11/11 存在性检查通过）。
- **单一口径确实单点**：`TaskSql.java:34` 的 `AT_RISK` 复用 `TaskRiskSpec.java:19` 的 `PENDING_ACCEPT_DAYS=3` 与 `OVERDUE`；`ThemeFilterSql` 同时被 `TaskQueryBuilder` 与 `GanttDao` 调用；`TaskRowMapper` 是唯一行映射器；`ThemeTag` 是唯一主题标签渲染点；前端无第二套主题色派生（softColor 仅透传）。
- **无死代码/断链**：TS `typecheck` 与 `build`、Java 全量测试均通过；S6~S10 的真实调用路径（批量建会、转任务、甘特、角色）全部走通，未见“文件在但调用不到”的情况。
- **架构观察（不计缺陷、不阻塞）**：`web/src/api/tasks.ts:59-74` 的 `taskApi.counters()` 用两次 `GET /tasks` 取逾期/待验收，未按蓝图走 `/tasks/counters`（后端未暴露，TECHNICAL 已记设计缺口）。它复用同一 SQL/同一 `today`，无第二套口径，调用点仅 `TaskPoolPage.vue:188`，因此判为可接受偏差；若后续做任务池性能优化，建议补该接口。
- **重复实现检查**：未发现第二个风险函数、第二个主题筛选拼装点、第二个甘特行映射器或第二套会议任务计数；`MeetingTaskProgress` 的四个数是 `computed` 一次算出，页面无 `filter().length`。

## 5. 缺陷表

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-01 | P3 | 系统设置·主题配置 | ADMIN 登录 → 系统设置 → 「主题配置」表格；或直接运行右侧命令 | 六列表头与单元格一一对齐（DESIGN.md:94 列宽 200/132/96/96/80/152），颜色名在「颜色」列内 | 名称与颜色两列 td 重叠（left 均 269），状态及之后每列整体左移一列；颜色名换行到名称下方（06-settings-themes.png 可见）。根因：`web/src/components/settings/ThemeConfigTable.vue:428/452` 对 `<td class="cell-name">`、`<td class="cell-color">` 直接设 `display:flex`，td 脱离表格布局 | AC-8 | `cd /Users/xuejunjun/Documents/deepseek-harness/project-pmo && NODE_PATH=/Users/xuejunjun/.npm-global/lib/node_modules node logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-settings-layout.cjs --base http://localhost:5174` | exit 0，输出 `PASS 6 columns aligned`；当前 exit 1，`FAIL 5 column(s) misaligned` |
| D-02 | P3 | 任务详情 | 打开任一挂了主题的任务详情（如 /tasks/10） | 主题标签在页头主展示一处即可 | 页头标签与「来源」行各渲染一次同名主题标签，信息重复 | AC-9 | - | - |
| D-03 | P3 | 任务详情 | 打开任务详情看「进度」卡 | 卡片标题与内部字段名不重复 | 卡片标题「进度」下方又出现一行字段名「进度」 | AC-6 | - | - |

> 证据质量备注：dev 截图 `13-gantt-day.png` 实际仍高亮「周」且窗口 12 周，文件名与内容不符；QA 用 S8 独立验证日档确为 14 天，故不记为产品缺陷，仅提示证据命名需校正。

## 6. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法/工具 |
|---|---|---|
| D-01 修复后的视觉复核 | 六列对齐、长主题名不挤压相邻列、1440/1512 宽无横向溢出 | 人工浏览器打开系统设置，或重跑 S11 后目视 |
| 移动端 375 宽 | 甘特横向滚动可用、左列不遮挡、会议表格可操作 | 真机/DevTools 375×812 |
| 读屏 | 五步条、四卡、主题标签、甘特条的 aria 文本可理解 | VoiceOver/NVDA |
| 动画与滚动流畅度 | 切档/翻屏无明显掉帧（脚本只断言请求数与耗时） | 真机录屏/Performance 面板 |
| 真实触控与安全区 | 100dvh、safe-area、多指滚动 | 真机 iOS/Android |

## 7. 证据与清洁度

- 探针数据全部清理：`tasks/themes/meetings` 中 `QA-R7-%` 计数均为 0；截断探针 305 条插入后已删除（残留 0）。
- 提交面：`git status --porcelain` 92 项，无 `node_modules/`、`target/`、`dist/`、`.pnpm-store/`、`.env`、`__pycache__/`、`.idea/` 命中；`.gitignore` 已覆盖 `logs/`、`.demo-shots`。
- 可复跑脚本：`logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-r7-boundary.py`、`qa-r7-meeting-ui.cjs`、`qa-r7-roles.cjs`、`qa-gantt-timing.cjs`、`qa-r7-truncate-ui.cjs`、`qa-settings-layout.cjs`。
