<!-- meta: summary="修复登录后会话 id 被逐请求轮换导致的并发 401：会话只在登录成功时轮换一次，登录后并发认证请求全部成功、登录态稳定" -->

基线依赖：docs/teamflow/20261110-r4-project-source-binding（当前 main 最新；其项目主数据、来源三态、任务池/矩阵/看板同源筛选口径不得回归）；其上游 docs/teamflow/20261109-r1-meeting-task-supervision（`AC-1` 登录与会话：7 天有效期、登出即失效、401 跳登录、CSRF 双提交 Cookie）与 docs/teamflow/20261109-r2-source-config-progress、docs/teamflow/20261110-r3-source-options-extract（来源/审计/前端投影）同样不得回归。

取代：docs/teamflow/20261109-r1-meeting-task-supervision#AC-1：会话 id 由「每个认证请求都可能被换发一次」改为「仅在登录成功时轮换一次」，其余会话口径（7 天有效期、登出即失效、Cookie 属性、401 语义）不变。

# PRD · 登录后并发请求 401 · 会话轮换竞态修复

## 1. 背景与目标

**现象**：admin 登录成功后，页面同时发出多个接口请求，其中部分返回 401，前端按既有拦截跳回 `/login?next=/tasks`；有时刷新一次又能正常进入。这类"偶发掉登录"直接打断派发人建任务、负责人写进度的动作，且无法通过重试培训规避——用户只看到"系统又把我踢出去了"。

**现场证据（2026-11-10，本机 18081 后端 + 5174 前端，jar 由当前 main 构建）**：

1. 登录拿到 `SUPERVISE_SESSION` 后，同一 Cookie 并行发起 5 个 `GET /api/auth/me` → **1 个 200 + 4 个 401**；那个 200 的响应头带 `Set-Cookie: SUPERVISE_SESSION=<新值>`，401 响应只带 `XSRF-TOKEN`。
2. 顺序复现：登录后第 1 个认证请求会换发新的 `SUPERVISE_SESSION`，第 2 个起稳定——即"新 cookie 只有跑到终点的那一个请求拿到，在途的其余请求手里是旧 id"。
3. 浏览器：Playwright 登录 POST 200 后，首屏并发 XHR（`/api/auth/me`、`/api/meta/options`、`/api/tasks`…）偶发整片 401，URL 停在 `/login?next=/tasks`；同一脚本重跑有时完全正常——典型竞态，不是稳定错误。

**根因（代码位置）**：身份只在请求内写入 `SecurityContextHolder`，从未写入会话的安全上下文仓库。

- `server/src/main/java/com/pmo/supervise/config/SessionAuthenticationFilter.java:29-35` 每个请求把会话里的 `Actor` 塞进 `SecurityContextHolder`，`finally` 清空，但不调用 `HttpSessionSecurityContextRepository.saveContext`，会话里没有 `SPRING_SECURITY_CONTEXT`。
- `server/src/main/java/com/pmo/supervise/common/AuthSession.java:29-33` 登录时只写自定义属性 `AUTH_USER` + 当前请求的 `SecurityContext`，同样不持久化安全上下文、也不显式轮换会话 id。
- `server/src/main/java/com/pmo/supervise/config/SecurityConfig.java:60` 用 `SessionCreationPolicy.IF_REQUIRED`，`:73` 把上面的过滤器插在 `UsernamePasswordAuthenticationFilter` 之前，未覆盖会话固定防护策略。
- 于是每个认证请求都满足"本次请求刚发生了新的认证、但仓库里没有上下文"，会话管理按默认的会话固定防护策略执行 `changeSessionId`：会话对象还在，**id 变了，旧 id 立即失效**。并发在途请求持旧 id → 401；唯一跑到最后的那个请求拿到新 `Set-Cookie` → 200。

**目标**：

1. 会话轮换只发生在登录成功那一刻（一次），认证请求不再轮换——登录后第一个请求与第 N 个请求用同一个会话 id。
2. 登录后并发请求全部成功：同一会话并行 5 个（并抽样 20 个）`/api/auth/me` 全部 200；浏览器登录后首屏并发请求不再整片 401。
3. 安全与既有语义零倒退：会话固定防护仍在（登录前后 id 必须不同）、401/登出/过期行为不变、CSRF 双提交契约不变、权限与审计口径不变。

**成功标准（可演示）**：`admin` 登录 → 立刻开 5 个并行的 `/api/auth/me` → 5 个全 200，响应里不再出现新的 `SUPERVISE_SESSION`；同一脚本连跑 20 轮无一次停在 `/login`；把浏览器 Cookie 里的 id 与登录响应里的 id 对比，完全一致。

## 2. 范围与非目标

**范围**

- 会话建立与轮换时机：密码登录、钉钉扫码、钉钉免登三条入口统一为"建立会话时轮换一次"。
- 认证过滤器与会话策略的调整（只改会话轮换行为，不改身份解析来源）。
- 并发回归用例（服务端真实并发）+ 全量测试回归 + 真机 curl 前后对比证据。
- 若调整涉及前端（预期不需要），只允许"保持既有 401 跳登录"，不加隐藏重试。

**非目标**

- 不引入 JWT、无状态鉴权或 Redis/数据库分布式会话；仍是单实例 Tomcat 内存会话。
- 不改会话有效期（`server.servlet.session.timeout=7d`）、Cookie 名称 `SUPERVISE_SESSION` 与属性（`HttpOnly; SameSite=Lax; Path=/`，HTTPS 下 `Secure`）。
- 不改 401 错误体结构（`{code,message,fieldErrors,details,traceId}`）、错误码集合、前端 `http.ts` 的归一化与跳转契约。
- 不改权限判定（`TaskAccessGuard`）、审计动作名与记录内容、任务/进度/矩阵/看板/逾期/日报的任何口径。
- 不做"同一账号多端登录踢旧会话"、不做登录设备列表、不做会话管理后台。
- 不改 CSRF 方案（仍是 `CookieCsrfTokenRepository.withHttpOnlyFalse()` 明文双提交）。

## 3. 反面目标（anti-goals / 反面目标）

以下每一条都是"测试全绿但人一眼判定失败"的结果，均可被使用者在几分钟内观察到：

1. **用前端兜底把竞态藏起来**：给 401 加自动重试、把首屏请求串行排队、或给 `/api/auth/me` 加"单飞+缓存"。表现：打开 Network 面板能看到同一个 `/api/auth/me` 出现两次，或首屏 10 个请求的耗时≈逐个相加；真实会话过期时页面变成反复重试/反复刷新而不是干净地回登录页。
2. **用关掉会话固定防护换"id 永不变"**：登录前后 `SUPERVISE_SESSION` 值完全相同（`curl -i` 前后对比即可看出）。这是在用一个安全缺陷掩盖一个功能缺陷，本轮的最初现象正是它造成的。
3. **概率只是变稀，没被消灭**：连跑 20 次"登录 → 立即 5 并发"，仍有 1 次出现 401 或停在 `/login?next=`。用户会认为"还是那个毛病，只是今天运气好"。
4. **放宽鉴权换取通过**：无 Cookie 的 `/api/tasks` 返回 200、登出后旧 Cookie 仍能取到数据、或把 `/api/auth/me` 挪进 `permitAll` 并用请求内缓存身份冒充已登录。表现：用 `curl` 不带 Cookie 访问任意业务接口不再 401。
5. **修完每个请求仍然重建/重写会话**：每个 200 响应仍带 `SUPERVISE_SESSION`（值变或不变都算），或每个认证请求都往会话里写一次"安全上下文"导致会话存储条目随并发增长、请求延迟肉眼变慢。表现：`curl -D -` 连续 10 个请求仍有 10 个 `Set-Cookie`。

## 4. 用户故事与验收标准

> 本需求夹内 AC 从 AC-1 连续编号；AC 即本需求的验收契约。全部 AC 的"会话 id"均指 Cookie `SUPERVISE_SESSION` 的值。

### US-1 登录后并发请求全部成功（P0）

作为派发人，我登录后立刻进任务池，页面并发拉取多个接口，我要它们全部成功，而不是被踢回登录页。

**AC-1 会话只在登录时轮换一次**

- 登录响应（200）下发 `SUPERVISE_SESSION=S0`；随后连续 10 次认证请求（`GET /api/auth/me`）均为 200，且**每个响应头都不含 `Set-Cookie: SUPERVISE_SESSION`**。
- 10 次之后再用 `S0` 发一次请求，仍为 200（证明"客户端没跟上的那种悄悄换值"不存在）。
- 同一会话的认证请求不新建会话：会话的创建时刻（`lastAccessed` 变化可接受）在登录时确定，请求期间不产生新的会话条目。
- 观测方式：`curl -D -` 逐条记录响应头；或自动化断言"登录后响应头中没有 `SUPERVISE_SESSION`"。

**AC-2 同一会话并发认证全部 200**

- 登录拿到 `S0` 后，用同一 Cookie **并行**发起 5 个 `GET /api/auth/me`：5/5 为 200，5 个响应体是同一账号（`username=admin`，`userId` 一致）。
- 稳定性抽样：同一"登录 → 立即 20 并发"动作重复 20 轮（合计 400 次请求），401 次数为 0。
- 并发必须是真并行（多线程/多协程 HTTP 客户端，请求同时在途），**顺序循环调用不算通过**。
- 修复后不允许出现"200 的响应里夹带新会话"——并发下也不应有任何一个响应下发 `SUPERVISE_SESSION`。

**AC-3 前端首屏并发不再被踢**

- 登录成功（200）后立即并发发出 ≥5 个首屏请求（至少含 `/api/auth/me`、`/api/meta/options`、`/api/tasks`），全部返回 2xx，浏览器停在角色落地页或 `next` 指定页，URL 不出现 `/login`。
- 连续 20 轮"登录 → 首屏并发"无一次跳登录；一致性要求与 AC-2 相同（偶发率不是"可接受"，是未修复）。
- 若环境无法启动 Chromium（见 §9 第 1 条），以真实 HTTP 并发 + 人工浏览器复测替代，并在 QA-REPORT 中写明替代方式与结论。

### US-2 安全边界不倒退（P0）

作为管理员，我要这次修复不把"防会话固定"和"登出即失效"一起弄丢。

**AC-4 会话固定防护保留：登录前后 id 必须不同**

- 场景：登录前已存在匿名会话（例如先打开登录页触发了写会话的入口，或钉钉 `state` 已写入会话）。
- 登录成功后 `SUPERVISE_SESSION` 的值必须与登录前的值**不同**（一次性轮换）。
- 且这次轮换是唯一的：登录后 10 次认证请求 id 不再变化（与 AC-1 同一断言）。
- 若登录前不存在任何会话，允许"登录直接新建会话"（此时无旧 id 可轮换），但登录响应必须下发 id。

**AC-5 401 与登出语义不回归**

- 无 Cookie 请求受保护接口 → `401`，响应体为既有结构且 `code=AUTH_REQUIRED`，不返回任何业务数据。
- 伪造/已失效的会话 id → `401`，行为同无 Cookie（不 500、不 302、不返回 HTML 登录页）。
- 登出（`POST /api/auth/logout`，204）后，旧 Cookie 再请求 → `401`；重新登录得到新会话可用。
- 前端 401 拦截保持现状：清空本地用户态并跳 `/login?next=<当前站内路径>`，`next` 仍走 `SessionUser.safeNext` 的站内校验（拒绝 `//evil`、反斜杠、CR/LF）。
- 401 响应不得携带 `SUPERVISE_SESSION`。

### US-3 其余会话入口同一口径（P1）

作为被改过角色的用户，我用钉钉扫码进来，也要享受同样的稳定性。

**AC-6 身份刷新与免登路径不引入新轮换点**

- 管理员修改某用户角色/姓名后，该用户下一次 `GET /api/auth/me` 返回最新角色（既有行为），期间**会话 id 不变**，且该请求与同时发出的其他认证请求并发时全部 200（角色刷新不得触发换发）。
- 钉钉扫码（`/api/auth/dingtalk/callback`）与钉钉内免登（`/api/auth/dingtalk/auto`）建立会话时轮换一次，之后稳定；钉钉未配置时仍按既有方式拒绝/降级（`AC-18`/`AC-21` 不回归）。
- 强制改密流程不变：`must_change_password=1` 的会话被 `MustChangePasswordFilter` 拦截为 409，改密成功后该会话继续可用、id 不因改密而变化。

**AC-7 CSRF 与写请求契约不变**

- `GET` 仍下发 `XSRF-TOKEN` cookie；带 `X-XSRF-TOKEN` 的写请求（`POST/PATCH/DELETE /api/tasks` 等）仍 2xx；缺失或错误 token 仍 `403`。
- 登录后首屏并发（GET）之后，第一个写请求无需刷新页面即可成功（CSRF cookie 抓取链路不因过滤器调整而断）。
- `CookieCsrfTokenRepository.withHttpOnlyFalse()` 与明文 `CsrfTokenRequestAttributeHandler` 不变。

### US-4 修复可被证明（P0）

作为验收人，我要看到"修好了"的证据，而不是"这次没复现"。

**AC-8 自动化并发用例 + 全量回归**

- 新增服务端自动化用例 ≥1，覆盖：同会话并行 5 个 `/api/auth/me` 全 200；并行 20 个全 200（抽样多轮）；登录后响应无 `SUPERVISE_SESSION`；登录前后 id 不同；无 Cookie/登出后 401。
- 该用例必须在**真实 Servlet 容器**里真并发（`@SpringBootTest(webEnvironment=RANDOM_PORT)` + 多线程 HTTP，或等价手段），不得以 `MockMvc` 顺序调用替代——`MockHttpSession` 不模拟"旧 id 失效"的容器语义，用它写的用例在缺陷存在时也会通过。
- 该用例在修复前应失败、修复后通过（提交说明/QA-REPORT 中记录前后对照）。
- 回归：`server/` 全量测试全绿（现有 247 例不得减少或转红，新增后总数增加）；`web/` 全量测试全绿（现有 174 例不得减少或转红）；`web` 类型检查/构建通过。

**AC-9 真机证据与口径零变化**

- 在 18081 后端上记录修复前（1×200 + 4×401）与修复后（5×200、20×200）的原始 `curl` 输出摘要，写入 QA-REPORT；`scripts/smoke-api.sh` 若可跑通，记录其结果。
- 明确不变：会话 7 天有效期、Cookie 属性、401 错误体、权限判定、审计动作名（`LOGIN_SUCCESS`/`LOGIN_FAILED`/`LOGOUT`/`PASSWORD_CHANGED` 等）、任务/进度/矩阵/看板/逾期/日报口径、错误码集合（不新增会话相关错误码）。
- 工作区在本轮结束后只包含：会话修复相关源码/配置、新增测试、本任务夹文档；无构建产物、无临时脚本、无日志文件进入提交。

## 5. 交互流程摘要

**修复前（现象）**：`POST /api/auth/login` → 200 + `SUPERVISE_SESSION=S0` → 前端并发 5 个请求 → 容器逐个处理，第一个把会话换成 `S1` 并回写 cookie，其余四个仍持 `S0` → 401 → 前端拦截跳 `/login?next=/tasks` → 用户刷新（此时浏览器可能已存 `S1`）→ 又能进。

**修复后（目标）**：

1. `POST /api/auth/login` → 200 + `SUPERVISE_SESSION=S1`（若登录前有匿名会话 `S0`，在此一次性换成 `S1`；没有则直接建 `S1`）。
2. 前端并发发出首屏请求（`/api/auth/me`、`/api/meta/options`、`/api/tasks`…）→ 全部 200，响应头不再出现 `SUPERVISE_SESSION`。
3. 用户停留期间任意次请求都用 `S1`；不再有"刷新一下又好了"的不可解释体验。
4. 会话真正过期/被登出 → 401 → 前端仍按既有逻辑跳 `/login?next=<原路径>`，登录后回到原页。
5. 管理员改角色 → 用户下一次 `/api/auth/me` 拿到新角色，会话 id 不变。

## 6. 优先级

| AC | 主题 | 优先级 |
|---|---|---|
| AC-1 / AC-2 | 会话只在登录时轮换一次、并发认证全 200 | P0 |
| AC-3 | 前端首屏并发不再被踢 | P0 |
| AC-4 / AC-5 | 会话固定防护保留、401/登出语义不回归 | P0 |
| AC-8 / AC-9 | 并发自动化用例与全量回归、真机证据与口径零变化 | P0 |
| AC-6 / AC-7 | 角色刷新与钉钉入口同口径、CSRF 契约不变 | P1 |

P0 缺任一项视为未交付；其中 AC-4 的"登录前后 id 必须不同"与 AC-1 的"登录后不再换发"必须同时成立——只满足其一是失败（对应反面目标 2）。AC-6/AC-7 可在 P0 通过后同迭代补齐，但 CSRF 一旦回归，所有写操作全站 403，视同 P0 失败。

## 7. 依赖与风险

**依赖**

- Spring Security 6.3（Boot 3.3.5）的过滤器链与会话管理：`SecurityConfig.java:60`（`IF_REQUIRED`）、`:73`（`addFilterBefore`）、`:74`（`CsrfCookieFilter`）。修复点落在"会话固定防护策略"与"安全上下文是否持久化"两处，二者都影响 `MustChangePasswordFilter` 与 `CsrfCookieFilter` 的执行顺序。
- 会话身份的唯一读取口 `AuthSession.current()`（`server/src/main/java/com/pmo/supervise/common/AuthSession.java:47-60`）与 `ActorArgumentResolver`：修复不得改身份来源，只改轮换时机。
- 既有测试基座 `server/src/test/java/com/pmo/supervise/IntegrationTestSupport.java`（连本地 MySQL `supervise_test`）与 `AuthApiTest`（MockMvc）；并发用例需要独立于 MockMvc 的启动方式。
- 前端 `web/src/api/http.ts` 的 401 拦截与 `XSRF-TOKEN` 回带逻辑（不改）。

**风险**

1. **只关策略不补显式轮换 → 安全回归**：若把会话固定防护设为 `none()` 却不在登录处显式 `changeSessionId`，则修复了并发却打开会话固定攻击面。缓解：AC-4 以"登录前后 id 必须不同"为硬断言，只在前者成立时才算修复。
2. **持久化安全上下文带来的写放大**：把 `SecurityContext` 存进会话会让每个请求都可能 `setAttribute`，在并发下产生会话属性竞争，且 AC-1 的"响应不带 `Set-Cookie`"仍可能被打破（会话被改动不等于换 id，但会造成会话写入抖动）。缓解：优先"只在登录轮换、请求期只读会话"的最小改法；若采用持久化方案，必须显式断言"认证请求不写会话"（AC-1 第 3 条）。
3. **MockMvc 给出假绿**：`MockHttpSession` 与 MockMvc 不模拟容器级会话 id 失效，顺序用例在缺陷存在时也通过。缓解：AC-8 强制真实容器并发，且要求"修复前失败"的前后对照。
4. **并发用例本身 flaky**：线程调度、连接池、测试库清理都可能让用例偶发红。缓解：固定轮次（5 并行必测 + 20×20 抽样）、失败时输出状态码与响应头分布（几 200 几 401）、单例总时长控制在数十秒内。
5. **过滤器顺序改动连带 CSRF**：`CsrfCookieFilter` 依赖 `CsrfFilter` 之后的属性解析；若调整顺序或提前短路，GET 不再下发 `XSRF-TOKEN`，前端首个写请求 403。缓解：AC-7 覆盖"并发 GET 之后立刻写"。
6. **历史会话与新行为不一致**：上线前已建立的会话（内存中、只有 `AUTH_USER`）在新逻辑下是否仍可用，取决于修复方式。缓解：按 §9 第 2 条默认"不强制重登"，并在实现中保证"只有 `AUTH_USER` 的会话仍被识别"。

## 8. 工程约束

- **原始需求的工程指令（逐字保留）**："用户已确认在本轮直接 main 上开发（branchPolicy=keep）。修复完成后请跑后端与前端现有测试、补并发用例，并报告改动与验证证据。" 即：不新建、不切换分支，在 main 上修复；修复完成后必须运行后端与前端现有测试，必须新增并发用例，必须报告改动与验证证据。
- **工作区现状**：分支 `main`，1 处未提交改动 `?? docs/teamflow/20261110-r5-session-rotation-race/`（本任务夹，未跟踪）。它与本次代码改动一并提交，不单独 stash、不丢弃；实现前不得回滚或删除该目录。近期提交（`019611d`、`c56d87d`、`1fccda3`）为 r4 场外改动，属基线，不回退、不改写。
- **提交（开发阶段动作）**：实现与测试通过后提交到 main，提交信息描述"会话轮换竞态修复 + 并发回归用例"，不夹带构建产物、依赖缓存、临时脚本与日志。
- **.gitignore 复核（开发阶段动作）**：本项目栈为 Maven（`server/`，含 `server/.mvn/repo|home` 重定向）+ Vite/pnpm（`web/`，`node_modules/`、`dist/`、`.pnpm-store/`）。现有 `.gitignore` 已覆盖上述目录、`*.tsbuildinfo`、`logs/`、`*.log`、`.env*`、`*.jar`、`*.class`、IDE 目录、`.DS_Store`。本轮需确认并补齐：真机验证产生的响应头采样/临时脚本必须落在 `logs/teamflow/<runId>/`（已忽略）或系统临时目录，不得散落项目根；`.demo-shots/` 目前只在 `.git/info/exclude`（本机私有、团队不可见），若本轮继续产出截图素材，应加入 `.gitignore` 并明确其为素材非交付物；若并发用例引入新的测试输出/缓存目录，同步补规则。
- **文档边界**：本需求全部契约文档只写 `docs/teamflow/20261110-r5-session-rotation-race/`；若"会话轮换只在登录时发生"被固化为新团队约定，则更新 `docs/teamflow/memory.md` 的同主题行（替换，不追加流水账）。不改写历史任务夹，不在项目根散落日志，不写宿主 `docs/<role>/`。

## 9. 假设与待澄清

已确认前提（不作为问题）：本轮在 main 直接开发（`branchPolicy=keep`）；目标行为是"会话轮换只在登录成功时发生一次"；验收口径为"登录后并发 5 个 `/api/auth/me` 全部 200、前端首屏并发无 401、顺序请求会话 id 稳定、现有 401 拦截跳登录不回归"；单实例内网部署、Tomcat 内存会话、7 天有效期、CSRF 明文双提交、不改钉钉集成范围——以上均为用户本轮已确认口径。

以下每条都是需要用户拍板或至少知情的口径（括号内为推荐默认值）：

- **（验收手段）真浏览器自动化能否作为必跑证据**：r4 记录本环境 Chromium 沙箱不可启动，若本轮同样不可用，AC-3 只能以"真实 HTTP 并发 + 人工浏览器复测"落地（推荐：HTTP 并发为准、Playwright 可用则加跑、人工复测列 M1；因为并发 401 的机制在 HTTP 层已可完整判定，浏览器只是同一 Cookie 的搬运者）。若要求必须浏览器自动化，则需先解决 Chromium 启动环境，本轮排期需加一段环境修复。
- **（上线）是否需要强制在线用户重新登录一次**（推荐：不需要。修复只改"何时轮换 id"，会话内容仍是 `AUTH_USER`，存量会话继续可用，用户无感）。若要求强制重登（例如引入会话版本号），代价是上线瞬间全员掉线一次，且需额外的会话失效机制，属范围扩大。
- **（前端兜底）是否给前端再加一层 401 保护**（推荐：不加。只保留既有的 401 跳登录；加自动重试/请求串行化/`me` 单飞会把本轮回归掩盖成"偶发重试"，与反面目标 1 冲突）。
- **（范围）轮换时机是否现在还扩展到改密与多端登录**（推荐：不扩展，本轮仅"登录成功轮换一次"；改密后不轮换、不踢其他会话，多端并存保持现状）。若要"改密后踢掉其它会话"，需要会话登记表与广播登出，是一次独立的安全增强而非本次缺陷修复。

## 10. 里程碑建议

- **M1 · 把竞态固化成红灯**：先写真容器并发用例（5 并行 / 20×20 抽样 / 登录后无 `Set-Cookie` / 登录前后 id 变化 / 401 三场景），在修复前跑出 1×200+4×401 的红灯并留证。演示：一条命令复现失败，失败信息里能数出几条 401。
- **M2 · 修复轮换时机（AC-1/AC-2）**：会话只在登录成功时轮换一次，认证请求不再换发；并发 5 与并发 20 全 200，顺序 10 次 id 不变。演示：`curl` 前后对比响应头。
- **M3 · 安全与入口回归（AC-4/AC-5/AC-6/AC-7）**：登录前后 id 必须不同；401/登出/过期不回归；角色刷新与钉钉扫码/免登同口径；CSRF 写请求仍通过。
- **M4 · 全量证据（AC-3/AC-8/AC-9）**：前端首屏并发复测（20 轮）、`server/` 与 `web/` 全量测试全绿、18081 真机记录、工作区清洁检查。
