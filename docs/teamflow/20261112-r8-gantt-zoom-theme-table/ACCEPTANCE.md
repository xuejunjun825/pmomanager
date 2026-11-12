# r8 验收报告（PM 终验）· 甘特三档整屏 + 主题配置表列对齐

- 需求夹：`docs/teamflow/20261112-r8-gantt-zoom-theme-table/`
- 基线：`1709f2a`（r7 验收未通过：A-01 甘特首屏无任务条 / A-02 主题配置表列错位）
- 验收方式：不采信 QA 结论本身，独立重跑几何探针、列对齐探针、首帧/请求探针、截图脚本、web/server 全量测试与 typecheck/build，并肉眼复核三张截图
- 环境：vite `http://localhost:5174`（/api 代理 8080）、Spring Boot 8080、MySQL `supervise`；真实 Chromium 1512×900；admin 登录态由探针自建

## 1. 逐条验收

| AC | 判据（PRD） | 本轮独立实测 | 结论 |
| --- | --- | --- | --- |
| AC-1 | 周档 84 天无横滚、dayW 10–16、visible>0 且 offRight=0 | `gantt-bars-geometry.cjs`：dayW=11（=floor(1238/84)）、scroll 1238/1238、visible 8/25、offRight=0、12 个周刻度、请求 1 次 | ✅ |
| AC-2 | 月档 182 天同规则，**含 month.offRight** | 同探针：dayW=5、1238/1238、visible 8/25、**offRight=0**、6 个月标签 | ✅ |
| AC-3 | 日档 14 天不回退、dayW≈68、可见且无横滚 | 同探针：dayW=68、1238/1238、visible 9/19、offRight=0、14 个日刻度 | ✅ |
| AC-4 | 条宽 max(10, 有效天数×dayW)、x≥0、x+width≤总宽+1、可点击 | 探针三档逐条断言 x/width/越界全过；`qa-r8-acceptance.cjs` 点击首条进入 `/tasks/30`（bars=25，首条 77px） | ✅ |
| AC-5 | 周/月刻度、标签矩形两两不相交、月标签≥6、今天线与周月带保留 | 探针逐对比较相邻刻度文字矩形（overlap=0），月档 labelled=6；月带/今天线在截图中可见 | ✅ |
| AC-6 | 三张截图档位与文件名一致、落盘前断言可见条>0、存 `.demo-shots/r8/` | 重跑 `gantt-shots.cjs` exit 0：week 150870B days=84 visible=8/25；day 154091B days=14 visible=9/19；month 148394B days=182 visible=8/25；三图肉眼复核**无空画布、无错档位** | ✅ |
| AC-7 | 6 列偏差≤6px、td=table-cell、flex 在内层 div、探针 exit 0 | 重跑 `qa-settings-layout.cjs` exit 0：th/td 左缘均为 `[269, 585.92, 795.09, 947.22, 1099.34, 1226.11]`（偏差 0）；6 个 td computed `table-cell`；`.cell-name__inner/.cell-color__inner` computed `flex`；表宽 1198 无溢出 | ✅ |
| AC-8 | 6 个 td + 内层 flex div 结构断言；web ≥300 全绿 | `theme-config.spec.ts:176` 断言表头顺序、首行 6 td、td 下仅一层 DIV.__inner、td 无内联 flex；`pnpm test` 38 文件 **306/306** 通过 | ✅ |
| AC-9 | server 305、web ≥300、typecheck/build 通过、diff 仅前端 | server surefire 52 类 **305/305**（failures=0 errors=0 skipped=0）；web 306/306；`pnpm typecheck` exit 0；`pnpm build` exit 0（1.37s）；`git diff --stat server/` 为空 | ✅ |
| AC-10 | 三档切换/翻屏单请求、步长=窗口天数、300 截断与逾期 pin 不变 | 探针：日/周/月切档各 1 个 `/api/gantt`；翻屏 requests=1、step=182 天；`git diff GanttPage.vue` 中 300 截断、pin、摘要筛选相关行零改动 | ✅ |
| AC-11 | 未测量容器不按 40px×天数撑宽、200ms 采样无横滚 | `qa-r8-acceptance.cjs` 以 16ms 采样 35 帧：maxOverflow=0，首帧 t=64ms 即 1238/1238；未测量回退 40/10/5 由 `useGanttWindow.ts:44` + 单测锁定 | ✅ |
| AC-12 | P3：两卡片标题去重（进度概览 / 流转记录） | `TaskDetailPage.vue:312` 进度概览、`:414` 流转记录；`t08-progress-mention.spec.ts:331` 断言同步；页面无用户可见旧标题 | ✅（可选已做） |

## 2. 架构一致性（M3）

| 蓝图要求 | 落地核对 | 结论 |
| --- | --- | --- |
| `useGanttWindow.ts` 为唯一日期↔像素纯函数层 | `dayWidth` 全库唯一实现（`grep` 仅 `useGanttWindow.ts:140`）；导出 `LEFT_COLUMN/MAX_DAY_WIDTH/UNMEASURED_DAY_WIDTH`；公式 `max(1,min(96,floor(timelineW/天数)))`，未测量按档位回退 | 符合 |
| 测量点移到 `.gantt__scroll`，由 GanttChart emit resize | `GanttChart.vue:98` 上报 `scrollEl.clientWidth`，`ResizeObserver` 在挂载/行数变化/容器重挂后 `nextTick` 复测；`GanttPage.vue:346` 接收；页面 canvas 测量链与 window resize 监听已删 | 符合 |
| `LEFT_COLUMN` 消除 280 三处字面量漂移 | GanttChart/GanttRow/GanttPage 均 import 常量，CSS 与 grid 不再写 280（仅注释保留字样） | 符合 |
| 列错位修复落在 DOM 层级 | `ThemeConfigTable.vue:235/242` 内层 `__inner` 承载 flex，`:437` td 显式 `table-cell`；`colgroup` 六列宽 200/132/96/96/80/152 未改 | 符合 |
| 零后端/接口/数据模型改动 | `git diff --stat server/` 空；接口契约与状态机无改动 | 符合 |
| 无重复实现/无适配漂移 | 无第二套日宽公式、无第二套测量路径、无复制表格组件；蓝图外仅一处必要修复（见 §3），收敛在 `GanttBar.vue` 内 | 符合 |

## 3. 缺陷与遗留

- 未发现 P0/P1/P2/P3 缺陷；r7 的 A-01、A-02 均已闭环。
- 附带修复（超出原蓝图、但为 AC-3 达标所必需）：`GanttBar.vue` 的 `.tip` 原 `visibility:hidden` 仍计入 `scrollWidth`，贴右缘时把日档撑宽；改为静止 `display:none` + 悬停 `flex`，并对右缘 300px 内任务条左镜像 tooltip。复测悬停最右条后 scroll 仍 1238/1238，不构成架构偏离。
- 人工补测（环境限制，非交付缺陷）：≤1024px 窄屏三档、`100dvh` 移动端安全区、tooltip 动画手感/FPS、VoiceOver/NVDA 朗读；本轮视口为 1512×900。
- 提交面：`git status` 仅 12 个改动文件（11 前端/测试 + memory）与未跟踪任务夹；`dist/`、`node_modules/`、`target/`、`logs/teamflow/`、`.demo-shots/` 均被 `.gitignore` 覆盖，无污染。
- memory 仅新增 1 行同主题缩放口径（含 tooltip 静止态约束），无变更日志式追加。

## 4. 复跑命令

```bash
cd web && pnpm test && pnpm typecheck && pnpm build        # 306/306, exit 0, exit 0
cd server && ./mvnw -q test                                # 305/305
NODE_PATH=$(npm root -g) node logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/gantt-bars-geometry.cjs --base http://localhost:5174
NODE_PATH=$(npm root -g) node logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/qa-settings-layout.cjs --base http://localhost:5174
NODE_PATH=$(npm root -g) node logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/gantt-shots.cjs --base http://localhost:5174 --out .demo-shots/r8
NODE_PATH=$(npm root -g) node logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/qa-r8-acceptance.cjs --base http://localhost:5174
```

验收结论：✅ 通过
