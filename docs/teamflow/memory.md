# 产品记忆 · 会议任务督办系统

## 技术栈与结构
- 后端 `server/`：Spring Boot 3.3.5 / Java 17 / MySQL 8 / Flyway V1–V3 / Spring Security（Session + CSRF Cookie）；前端 `web/`：Vue 3 + Vite + TypeScript + Pinia；单实例，`@Scheduled` 承担扫描与报表。
- 交付：README（启动、时区、admin 初始口令 `Admin@12345` 首登改密、钉钉配置清单）、docker-compose、deploy/ 示例、scripts/smoke-api.sh。

## 团队约定
- 「今天」唯一来源 `ShanghaiClock.today()`（`Clock` 可注入），每请求取一次并作为参数贯穿查询；SQL 禁止 `CURDATE()`。
- 逾期谓词唯一常量 `TaskSql.*`（任务池/详情/矩阵/看板/日报/扫描/查询构建器复用）；Java 侧 `OverdueSpec` 由 `OverdueSqlParityTest` 15 组逐条对照。
- 权限唯一判定点 `TaskAccessGuard`（8 个服务引用）；越权 403、owner 越界 404；Controller 不写业务分支。
- 通知幂等：`dedup_key` 唯一键 INSERT-catch；扫描/报表一天一次由 `job_runs(job_key,run_date)` 主键保证。
- 钉钉未配置：`DingTalkProperties` 判定，渠道状态 `NOT_CONFIGURED` 不重试；入口隐藏、推送跳过、业务零 500。
- 来源类型唯一配置源（r2 起）：管理端维护、任务只存编码；内置 `MEETING/PROJECT/DAILY` 不可删，「会议决议」不可停用；任务进度 append-only 不改状态，@ 通知按（进度, 被 @ 人）幂等，被 @ 人获得该任务只读可见与续写进度权。
- 错误体统一 `{code,message,fieldErrors,details,traceId}`，前端 `http.ts` 归一化；会话 7d，CSRF 使用明文 token 契约（`CsrfTokenRequestAttributeHandler`）。
- 契约文档只写 `docs/teamflow/<yyyyMMdd-rN-slug>/`；跨需求记忆在本文件。

## 已知待办
- 真实钉钉扫码/工作通知送达需凭证补测（QA-REPORT §5 M2/M3）。
- 真浏览器视觉与可访问性（M1/M5/M6）、真时钟 09:00 / 周一 09:00 触发（M4）待人工复核。
- AC-1「钉钉侧退出后系统会话同步失效」：TECHNICAL 称前端 `pagehide` 尽力登出，但 `web/src` 未实现该监听，需补监听或修正文档表述（P3，不阻断）。
- r2 验收遗留 R-1（有条件通过项）：前端来源选项投影（启用优先/停用标注）在任务池筛选、矩阵、看板三处重复，应抽公共 helper 并补单测后复验。
