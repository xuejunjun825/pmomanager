# r8 验收报告 · 甘特三档整屏 + 主题配置表列对齐

- 需求夹：`docs/teamflow/20261112-r8-gantt-zoom-theme-table/`
- 基线：`1709f2a`（r7，验收未通过：A-01 甘特首屏无任务条 / A-02 主题配置表列错位）
- 验收角色：QA（独立复测，不复制开发断言；探针自建）
- 结论：**可验收**。AC-1 ~ AC-12 全部通过，未发现 P0/P1/P2/P3 缺陷。

## 1. 范围与环境

| 项 | 内容 |
| --- | --- |
| 交付范围 | 前端 11 文件 + `docs/teamflow/memory.md`；`git diff --stat server/` 为 0 行（零后端/接口/数据模型改动） |
| 运行时 | vite `http://localhost:5174`（`/api` 代理 8080）、Spring Boot 8080、MySQL `supervise`（flyway v7，含 themes/projects） |
| 浏览器 | 真实 Chromium headless（全局 Playwright `/Users/xuejunjun/.npm-global/lib/node_modules`），视口 1512×900，可执行像素断言与截图 |
| 数据 | 窗口内 25 条任务、未归主题 17 条、跨 3 个主题；三档窗口 84 / 14 / 182 天 |
| 登录态 | admin（CSRF 流程），页面会话由探针自身登录建立 |

## 2. 用例与结果

| 编号 | 场景 | 判据 | 执行证据 | 结果 |
| --- | --- | --- | --- | --- |
| AC-1 | 周档整屏 | 84 天不横滚、dayW 10–16、visible>0 且 offRight=0 | `gantt-bars-geometry.cjs`：dayW=11、scroll 1238/1238、visible 8/25、offRight=0、刻度 12 周 | PASS |
| AC-2 | 月档整屏 | 182 天同规则、dayW 4–8、补 month 断言 | 同上：dayW=5、1238/1238、visible 8/25、offRight=0、刻度 6 个月标签 | PASS |
| AC-3 | 日档不回退 | 14 天、dayW≈68、可见且不横滚 | 同上：dayW=68、1238/1238、visible 9/19、offRight=0、刻度 14 天 | PASS |
| AC-4 | 条几何与可点击 | 宽 max(10, 有效天数×dayW)、x≥0、x+width≤总宽+1、点击进详情 | 探针三档条宽/越界断言全过；`qa-r8-acceptance.cjs` 点击首条 → `/tasks/31`（bars=25，首条 55px） | PASS |
| AC-5 | 刻度可读性 | 按周/月出刻度、标签矩形不相交、今天线与周月带保留 | `gantt-bars-geometry.cjs` 对三档逐对比较刻度矩形与月带，无交叠；12 周 / 14 天 / 6 月标签 | PASS |
| AC-6 | 截图证据 | 三张档位与文件名一致、落盘前断言可见条>0 | `gantt-shots.cjs` exit 0：`13-gantt-week.png` 150870B（84 天）、`13-gantt-day.png` 154091B（14 天）、`13-gantt-month.png` 148394B（182 天），均 visible>0/offRight=0；三图已人工查看，无空画布、无错档位 | PASS |
| AC-7 | 主题配置表 6 列对齐 | 列偏差≤6px、名称/颜色不重叠、td=table-cell、探针 exit 0 | `qa-settings-layout.cjs` exit 0：th 左缘 `[269, 585.92, 795.09, 947.22, 1099.34, 1226.11]` 与 td 逐列一致（偏差 0），td computed display 全 `table-cell`，内层 `div` 全 `flex` | PASS |
| AC-8 | 组件结构测试 | 断言 6 个 td + 内层 flex div；web ≥300 全绿 | `theme-config.spec.ts` 新增列结构例（表头序列/6 td/内层 DIV.__inner）；`cd web && pnpm test` 38 files / 306 tests passed | PASS |
| AC-9 | 基线不回退 | server 305、web ≥300、typecheck/build 通过、diff 仅前端 | server `./mvnw -q test` surefire tests=305 failures=0；web 306；`pnpm typecheck`、`pnpm build` 均 exit 0；`git diff --stat server/` 空 | PASS |
| AC-10 | 请求与口径不变 | 三档切换/翻屏单请求、步长=窗口天数、300 截断与逾期 pin 不变 | `qa-r8-acceptance.cjs`：日/周/月切换各 1 个 `/api/gantt`；`gantt-bars-geometry.cjs` 翻屏 requests=1、step=182；`GanttPage.vue` 300 截断与 pin 相关逻辑 diff 为空 | PASS |
| AC-11 | 首帧不撑宽 | 未测量容器不按 40px×天数渲染、200ms 采样无横滚 | `qa-r8-acceptance.cjs` 用 `addInitScript` 以 16ms 采样：34 帧、maxOverflow=0、首帧 75ms 即 1238/1238；未测量回退 40/10/5 由 `gantt-window.spec.ts` 断言 | PASS |
| AC-12 | P3 文案去重（可选） | 任务详情两卡片标题为「进度概览」/「流转记录」 | `TaskDetailPage.vue:312/:414` 已改，`t08-progress-mention.spec.ts:331` 断言同步；页内仅 3 处注释保留旧词，非用户可见 | PASS |

## 3. 缺陷

未发现缺陷（P0/P1/P2/P3 均无）。r7 的两个返工项均已闭环：

- A-01（P0 首屏无任务条）：`dayWidth = max(1, min(96, floor(timelineW/天数)))`，滚动视口宽度由 `GanttChart` 观测 `.gantt__scroll` 后 `emit('resize')` 上报，`LEFT_COLUMN + 天数×dayW ≤ 视口宽` 在 1512×900 下三档恒成立（1238/1238）。
- A-02（P2 列错位）：flex 从 `td` 下沉到 `div.cell-name__inner/.cell-color__inner`，`td` 显式 `table-cell`；6 列 th/td 左缘完全一致。

> 附带修复（T5，超出原蓝图但为达标所必需）：`GanttBar.vue` 的隐藏 `.tip` 原以 `visibility:hidden` 参与 scrollWidth，贴右缘时把日档撑到 1324；改为静止 `display:none` + 悬停 `flex`，并对右缘 300px 内任务条镜像 tooltip。复测悬停最右条后 `scrollWidth` 仍 1238，回归探针已覆盖。

## 4. 架构核对（M3）

| 蓝图要求 | 落地情况 | 结论 |
| --- | --- | --- |
| `useGanttWindow.ts` 为唯一日期↔像素纯函数层 | 导出 `LEFT_COLUMN`/`MAX_DAY_WIDTH`/`UNMEASURED_DAY_WIDTH`/`dayWidth`/`barGeometry`/`buildTicks`/`monthBands`/`windowDays`；`dayWidth` 全库唯一实现 | 符合 |
| 测量点移到 `.gantt__scroll`，由 `GanttChart` emit resize | `GanttChart.vue:98` `emit('resize', scrollEl.clientWidth)`；`GanttPage.vue:346` `@resize="onResize"`；canvas 测量链已删 | 符合 |
| `LEFT_COLUMN` 消除 280 字面量漂移 | `grep -n "280" web/src` 仅常量定义与注释；GanttChart/GanttRow/GanttPage/测试均导入常量 | 符合 |
| 列错位修复落在 DOM 层级而非列宽数值 | `ThemeConfigTable.vue` td 加内层 div 承载 flex；`colgroup` 六列宽 200/132/96/96/80/152 未动 | 符合 |
| 零后端改动 | `git diff --stat server/` 为空 | 符合 |
| 无重复实现/无抽象缺失 | 未发现第二套日宽公式、第二套测量路径或复制粘贴的表格组件；工具条 tooltip 修复收敛在 GanttBar 内 | 符合 |

## 5. 接口一致性静态核对

| 调用点 | 被调成员 | 证据 | 结果 |
| --- | --- | --- | --- |
| `GanttPage.vue` | `dayWidth(win, timelineW)` | `useGanttWindow.ts:140` 导出，签名 `(win, timelineWidth: number): number` | 一致 |
| `GanttChart.vue` | `emit('resize', width)` | 声明 `:35`、发出 `:98`；页面 `@resize="onResize"` 接收 `(width: number)` | 一致 |
| `GanttRow.vue → GanttBar.vue` | `laneWidth` prop | `GanttRow.vue:40/:100` 传入，`GanttBar.vue:18` 声明 `laneWidth: number` | 一致 |
| `GanttChart/GanttRow/GanttPage` | `LEFT_COLUMN` | 均 `import { LEFT_COLUMN } from '@/composables/useGanttWindow'`，该常量 `:39` 导出 | 一致 |
| 测试 | `UNMEASURED_DAY_WIDTH` | `gantt-window.spec.ts` 引用，`:44` 导出 | 一致 |

## 6. 提交面卫生

`git status --porcelain` 仅 12 个已改文件（11 前端 + memory）与未跟踪任务夹；无 `node_modules/`、`dist/`、`server/target/`、`.idea/`、`.env*`。`.gitignore` 已覆盖 `logs/teamflow/`、`.demo-shots/`、`node_modules/`、`dist/`、`target/`、`.pnpm-store/`、`*.tsbuildinfo`、`.env`、`*.log`，无需新增条目。

## 7. 工艺观察（P3，仅观察）

- 甘特页：loading / error / 空态 / 300 截断提示齐备；任务条 hover 与键盘 focus 均出 tooltip，`prefers-reduced-motion` 下关闭动画；三档切换后滚动位置归零，未见遮挡或错位。
- 主题配置表：排序内联编辑、停用/删除确认、加载/错误态均可用；列对齐后省略号与色点未丢。
- 未发现工艺缺口，无 P3 行。

## 8. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收判据 | 方法/工具 |
| --- | --- | --- |
| 移动端/窄屏（≤1024px）三档甘特 | 首屏仍无横滚、左侧列不挤压时间轴 | 真机 Chrome DevTools 设备模拟 + 手测（本轮视口 1512×900） |
| `100dvh` 动态工具栏与安全区 | 底部/顶部不被浏览器栏遮挡 | iPhone Safari 真机 |
| tooltip 动画手感与切档 FPS | 60fps 无明显掉帧 | DevTools Performance 录制 |
| 屏幕阅读器朗读任务条状态 | 键盘 Tab 可达、状态与日期被正确朗读 | VoiceOver / NVDA |

## 9. 复跑命令

```bash
cd web && pnpm test                       # 306/306
cd web && pnpm typecheck                  # exit 0
cd web && pnpm build                      # exit 0
cd server && ./mvnw -q test               # 305/305
NODE_PATH=$(npm root -g) node logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/gantt-bars-geometry.cjs --base http://localhost:5174
NODE_PATH=$(npm root -g) node logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/qa-settings-layout.cjs --base http://localhost:5174
NODE_PATH=$(npm root -g) node logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/gantt-shots.cjs --base http://localhost:5174 --out .demo-shots/r8
NODE_PATH=$(npm root -g) node logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/qa-r8-acceptance.cjs --base http://localhost:5174
```
