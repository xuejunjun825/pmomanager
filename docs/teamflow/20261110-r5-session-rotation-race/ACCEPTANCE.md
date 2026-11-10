# 验收报告 · r5 会话轮换竞态修复

- 需求夹：`docs/teamflow/20261110-r5-session-rotation-race/`
- 验收人：产品经理（acceptance lead）
- 日期：2026-11-10
- 被测版本：工作区未提交改动（3 个生产文件 + 1 个服务端用例 + 1 个前端 spec + 文档），基线 HEAD `019611d`
- 证据来源：QA 阶段未产出有效报告（`stopReason=error`、正文 0 字符），本报告的结论由验收人独立复核得出：
  ① 现场两个实例实测（`18095` = 工作区修复版，`18096` = HEAD 缺陷版，均为 QA 阶段遗留进程）；
  ② `logs/teamflow/tf-mwaaqll7-nadfak/evidence/` 下的探针原始输出；
  ③ 验收人本轮重跑的全量测试与新增的“浏览器式 Cookie 罐”探针
  `logs/teamflow/tf-mwaaqll7-nadfak/scripts/acceptance-ac7-browser-jar.py`。

## 1. 结论摘要

- **目标缺陷已修复，且是确定性的而非“概率变稀”**：缺陷侧 20 轮 × 20 并发 = 400 次认证，`ok=4 / 401=396`、认证请求仍在换发会话 id；修复侧 `ok=400 / 401=0`，400 个响应**零** `Set-Cookie: SUPERVISE_SESSION`。登录时恰好换发一次（`01006C47…` → `2ACE192A…`），缺陷侧登录前后 id 完全相同（`9E5B1282…` 不变）。
- **回归全绿**：`server` 248/248（既有 247 + 新增 1，0 失败）、`web` 177/177（24 文件，既有 174 + 新增 3）、`vue-tsc --noEmit` 与 `pnpm build` 退出码 0。
- **有 1 条 P1 未达成（AC-7 第三句）**：首屏并发 GET 之后，第一个写请求实测 **0/20 通过**（403 `FORBIDDEN`）。同一探针在 HEAD 缺陷版上同样 0/20 → **是既存缺陷，不是本轮引入的回归**；但本需求的 AC-7 文本明确要求它成立，且新增用例对 AC-7 的断言弱于 AC 语义（见 C1）。
- **真浏览器复测未做**（Chromium 在沙箱内不可启动），AC-3 只到“真实 HTTP 并发”层。

## 2. AC 逐条核对

| AC | 级别 | 结论 | 实测证据 | 备注 |
| --- | --- | --- | --- | --- |
| AC-1 会话只在登录时轮换一次 | P0 | ✅ 达成 | 登录后顺序 10 次 `/api/auth/me` 全 200，`SUPERVISE_SESSION` 下发计数 **0**（`evidence/qa-after.json` → `sequential.rotationCount=0`）；同一断言在真容器用例 `SessionRotationRaceTest.java:98-108` | 登录后 10 次再请求仍 200；认证请求不新建会话（id 始终是登录下发的那个） |
| AC-2 同会话并发全部 200 | P0 | ✅ 达成 | 20 轮 × 20 并发 = 400 次：`200×400`、`401=0`、`rotation=0`（`qa-after.json` → `burst`）；5 并发用例断言账号 id 一致（`SessionRotationRaceTest.java:110-118`） | 真多线程（`receive`/`CountDownLatch`），非顺序循环；并发响应无一会话 Cookie |
| AC-3 前端首屏并发不再被踢 | P0 | ⚠️ 部分达成 | 6 个首屏端点（`/api/auth/me`、`/api/meta/options`、`/api/tasks`×4）× 20 轮全部 2xx、`rotation=0`、`failures=[]`（`evidence/qa-front-screen-after.json`） | 前端生产代码零改动，`ensureLoaded` 仍 `Promise.allSettled` 并发（`web/src/stores/auth.ts:31-37`）；**浏览器层未复测**（Chromium 不可用）→ C2 |
| AC-4 会话固定防护保留 | P0 | ✅ 达成 | 匿名会话 `S0=01006C47…` → 登录后 `S1=2ACE192A…`，`login.rotationCount=1`、`rotated=true`；Cookie 属性 `Path=/; HttpOnly; SameSite=Lax` | 缺陷侧此处 `rotated=false`（登录不换发），修复侧恰好一次；AC-1 与 AC-4 同时成立 |
| AC-5 401/登出语义不回归 | P0 | ✅ 达成 | 实测 18095：匿名 `/api/tasks` → 401；伪造会话 → 401 `AUTH_REQUIRED` 且无会话 Cookie；登出 204 后旧 Cookie → 401；401 响应体字段不变、不含 `SUPERVISE_SESSION` | 前端 401 拦截与 `SessionUser.safeNext` 未改动；`next` 站内校验沿用既有实现 |
| AC-6 身份刷新/免登不引入新轮换点 | P1 | ✅ 达成 | 改 `users.role` 后 `/api/auth/me` 返回 `EXEC` 且无会话 Cookie（`SessionRotationRaceTest.java:139-152`）；改密走 `AuthService.java:104` 的 `signIn`（不含换发）；钉钉扫码/免登汇合于 `AuthService.java:72-73` 的 `establish` 首行 | 钉钉未配置时既有降级未变（`DingTalkOAuthApiTest` 5 例全绿）；`must_change_password` 409 拦截沿用 `AuthApiTest.java:135-148` |
| AC-7 CSRF 与写请求契约不变 | P1 | ❌ 部分不达成 | 前两句成立：`GET` 下发 `XSRF-TOKEN`；带 token 的 `POST` 通过 CSRF（实测落到 422 业务校验）；缺 token → 403 `FORBIDDEN`。**第三句不成立**：首屏 6 并发 GET 后首个 `POST /api/tasks` **0/20 通过**（403，18095 与 18096 逐字一致） | 机制：每个认证请求仍被会话管理视为“本请求刚认证”，其 CSRF 轮换把 `XSRF-TOKEN` 清空（`Set-Cookie: XSRF-TOKEN=; Max-Age=0`），6 个并发 GET 都携带 token → 全部清空 → 写请求无 token。HEAD 版同样如此，故非本轮回归，但 AC 未达成 |
| AC-8 自动化并发用例 + 全量回归 | P0 | ✅ 达成 | 新增 `SessionRotationRaceTest`（`RANDOM_PORT` 真容器 + 手工 Cookie 串 + 20 线程池，`SessionRotationRaceTest.java:94-160`）；`server` 248/248、`web` 177/177、类型检查/构建通过；修复前后对照：缺陷版探针 9/15 断言通过（6 条失败），修复版 15/15 | 用例覆盖登录一次换发、顺序 10 次、5 并发、20×20、角色刷新、401、CSRF；唯一缺口见 C1 的断言强度 |
| AC-9 真机证据与口径零变化 | P0 | ⚠️ 替代满足 | 18081 的 `supervise-1.0.0.jar` 经 `javap` 核实已含本轮修复，且库中 admin 口令被外部改过，无法充当“修复前”现场 → 改用 A/B 同构（18182 = HEAD 源码 / 18183 = 工作区，同库结构，`evidence/compare.md` §1）；18081 修复后 15/15 | 口径对照 6 项无变化：会话 TTL `7d`（`server/src/main/resources/application.yml:6`）、Cookie 属性、401 错误体、`/me` 权限 `ADMIN`、审计 `LOGIN_SUCCESS=4 / LOGOUT=2` 两侧一致、错误码集合 `{AUTH_REQUIRED, FORBIDDEN}` 未新增 |

## 3. 架构一致性（M3 质量门）

- **单一换发点，无重复实现**：`AuthSession.rotateSessionId`（`common/AuthSession.java:32-37`）只被登录汇合点 `AuthService.establish` 首行调用（`auth/AuthService.java:73`），刻意与 `signIn` 解耦，因此 `me()` 的角色刷新不会换 id；表单登录 / 钉钉扫码 / 钉钉免登三条入口同口径，没有各写一套。
- **配置意图有据可查**：`SecurityConfig.java:48` 构造 `NullSecurityContextRepository`，`:61-65` 显式 `sessionFixation(none())`，`:68` 设载入侧仓库、`:82` 在 `build()` 前设共享仓库——两处都留了“为什么必须双侧”的注释，未出现适配层漂移；零 DDL、零路由、前端生产代码零改动。
- **但结构上留了一处副作用**：用 `NullSecurityContextRepository` 让容器不再持久化安全上下文，代价是 `SessionManagementFilter` 仍把每个认证请求当作“新认证”，其 CSRF 轮换继续清空 `XSRF-TOKEN`（无 token 时再补发）。这正是 AC-7 第三句失败的机制，属“应随本轮一并收敛”的实现质量问题，而不是纯外部因素 → 列为 C1。

## 4. 条件项与遗留

- **C1（P1，通过条件）AC-7 第三句未达成**：首屏并发后首个写请求 403（0/20，两版一致）。建议二选一收敛：①让会话管理不再把已认证请求当作新认证（自定义 `SecurityContextRepository.containsContext` 在请求内已认证时返回 true）；②在响应清空 `XSRF-TOKEN` 时补发新 token（替换/后置 `CsrfAuthenticationStrategy`）。**同时**把 AC-7 的自动化改成浏览器 Cookie 罐语义——现用例 `SessionRotationRaceTest.java:149-160` 把登录前 token 同时放进 Cookie 与 `X-XSRF-TOKEN` 头，绕过了“响应清空 Cookie”这一事实，因此缺陷存在时也会绿。
- **C2（P2）真浏览器人工复测**：Chromium 不可启动，AC-3 只到 HTTP 并发层。需人工走一遍：登录 → 首屏并发 → 新建/编辑任务（写请求）→ 管理员改角色后刷新 → 登出。建议与 C1 一起复测，因为 C1 的表现正是“点保存报 403”。
- **C3（流程）QA-REPORT 缺失**：QA 阶段正文 0 字符，任务夹只有 PRD/TECHNICAL。本报告已承担其证据职责；若流程强制要求 QA-REPORT，请补写或明确以本报告为唯一验收记录。
- **P3 环境/工作区**：`.gitignore` 新增 `.demo-shots/` 与会话修复无直接关系（无害，建议保留）；验收时仍有 7 个临时实例在监听（18081/18090/18091/18093/18094/18095/18096），建议收尾停止，避免下一轮取证再被“旧进程/旧库”干扰（本轮已因此把 18081 换成 A/B 同构）。

## 5. 反面目标核对（PRD §3）

| 反面目标 | 是否发生 | 依据 |
| --- | --- | --- |
| 1. 前端重试/串行化藏竞态 | 否 | 前端生产代码零改动，`ensureLoaded` 仍并发；未加 401 重试 |
| 2. 关掉会话固定防护换“id 永不变” | 否 | 登录前后 id 必不同且只换一次（AC-4 实测） |
| 3. 概率只是变稀 | 否 | 400/400 全 200，20 轮零 401，与缺陷侧 4/400 形成确定性对照 |
| 4. 放宽鉴权换通过 | 否 | 匿名/伪造/登出后仍 401，受保护接口未进 `permitAll` |
| 5. 修完每请求仍重建/重写会话 | 否（会话层） | 认证响应 `Set-Cookie: SUPERVISE_SESSION` 计数 0；CSRF cookie 仍被每请求清空/补发 → C1 |

## 6. 复现方式

```bash
# 起点：修复后实例 18095、缺陷侧 18096（HEAD 编译）均在运行
BASE_URL=http://127.0.0.1:18095 python3 logs/teamflow/tf-mwaaqll7-nadfak/scripts/session-rotation-probe.py \
  --rounds 20 --concurrency 20 --assert   # 15/15，exit 0
BASE_URL=http://127.0.0.1:18096 python3 logs/teamflow/tf-mwaaqll7-nadfak/scripts/session-rotation-probe.py \
  --rounds 20 --concurrency 20 --assert   # 9/15，exit 1（缺陷复现）
python3 logs/teamflow/tf-mwaaqll7-nadfak/scripts/acceptance-ac7-browser-jar.py http://127.0.0.1:18095
# → FIRST-WRITE-after-concurrent 403 FORBIDDEN（18096 同）
cd server && ./mvnw -q test        # 248/248
cd web && pnpm test && pnpm exec vue-tsc --noEmit && pnpm build   # 177/177 + 类型/构建通过
```

验收结论：⚠️ 有条件通过
