# 技术方案 · 会话轮换竞态（登录后并发 401）

> 任务夹：`docs/teamflow/20261110-r5-session-rotation-race/`
> 基线：`docs/teamflow/20261110-r4-project-source-binding`
> 取代：`docs/teamflow/20261109-r1-meeting-task-supervision#AC-1`
> 分支：main 直接开发（branchPolicy=keep，不建/不切分支）

## 0. 结论

一句话：**把"会话 id 换发"从框架的每请求默认策略，收回到登录成功这一个显式动作**——关掉 `sessionFixation().changeSessionId()` 自动策略，改由 `AuthService.establish` 调 `request.changeSessionId()`；并把 `SecurityContextRepository` 两侧显式置为 `NullSecurityContextRepository`，让身份唯一来自 `AUTH_USER`。

改动面：3 个生产文件（`AuthSession.java` +8 行、`AuthService.java` +1 行、`SecurityConfig.java` +7/−1 行）、1 个新增真容器测试、1 个前端防回归 spec、1 个探针脚本原地增强。**零 DDL、零迁移、零接口变更、前端生产代码零改动。**

## 1. 根因

### 1.1 现象与代码的对应

现象：登录后 5 并发 `GET /api/auth/me` = 1×200 + 4×401，且那个 200 的响应带**新的** `Set-Cookie: SUPERVISE_SESSION`。

链条（每一环都有代码位置）：

1. `config/SessionAuthenticationFilter.java:29-35` 每个请求从会话属性 `AUTH_USER` 还原 `Authentication` 写进 `SecurityContextHolder`，`finally` 里清空；
2. 该上下文**从不落会话**：`SecurityConfig.java` 未把 `requireExplicitSave` 设为 false，Spring Security 6.3 默认用 `SecurityContextHolderFilter`（只 load、不 save），仓库里也没有任何地方调用 `saveContext`；
3. 于是 `SessionManagementFilter` 每次请求 `containsContext(request)` 都为 false，把"本请求内刚刚有认证"误判为"刚刚发生新认证"；
4. 默认 `sessionFixation=changeSessionId` → **每个认证请求都执行 `request.changeSessionId()`**：会话对象还在、id 变了；
5. 在途的其它请求已经握着旧 id 进来，容器按旧 id 找不到会话 → 401；唯一跑完的请求拿到新 id 的 `Set-Cookie`。

这同时解释了"1 个成功 + 4 个 401"和"200 的响应带新 cookie"两个细节，也解释了"刷新一下有时又好了"（浏览器已存下新 id）。

### 1.2 为什么不能只在 `me()` 里换

`auth/AuthService.java:101-104` 在角色变化时也调 `AuthSession.signIn(request, fresh)`。只要"换 id"还挂在 `signIn` 上，角色刷新就会变成第二个换发点，直接违反 AC-6（刷新角色 id 不变）。**换发必须与"写身份"解耦。**

### 1.3 三个候选点，只留一个

| 候选 | 结论 |
|---|---|
| 框架自动 `changeSessionId` | 关闭（`sessionFixation().none()`）——它是竞态源 |
| 登录成功时显式 `request.changeSessionId()` | 保留——唯一换发点，满足 AC-4 |
| 角色刷新 / 改密 / 钉钉刷新 | 不换发（AC-6；改密本来就不换） |

## 2. 方案

### 2.1 不变量

- **I1** 身份唯一事实源 = 会话属性 `AUTH_USER`（`AuthSession.ATTR`）；`SecurityContext` 只是请求内的派生视图。
- **I2** 一次登录生命周期内 `SUPERVISE_SESSION` 值恒定；只有登录成功那一次响应带新的 `SUPERVISE_SESSION`。
- **I3** 登录前若已有会话（钉钉 state / CSRF 等造成），登录后 id 必须不同——会话固定防护不减配。
- **I4** 401 / 登出 / 过期 / 权限 / 审计 / CSRF 契约零变化。

### 2.2 变更 1：`AuthSession` 增加显式换发

文件 `server/src/main/java/com/pmo/supervise/common/AuthSession.java`（`ATTR` 常量之后）：

```java
    /**
     * 登录成功时换发会话 id —— 会话固定防护的唯一入口。
     * 无会话时不动：新会话 id 由随后的 getSession(true) 生成，登录后自然与匿名期不同。
     */
    public static void rotateSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            request.changeSessionId();   // 只改 id，属性全部保留；容器据此下发新 Set-Cookie
        }
    }
```

唯一容易写错的点：`HttpServletRequest.changeSessionId()` 在**没有会话时抛 `IllegalStateException`**（→500），必须先判空。

### 2.3 变更 2：`AuthService.establish` 里调一次

文件 `server/src/main/java/com/pmo/supervise/auth/AuthService.java:72`（`establish` 首行）：

```java
    public AuthUser establish(User user, HttpServletRequest request) {
        AuthSession.rotateSessionId(request);   // 唯一换发点：表单登录 / 钉钉扫码 / 免登同口径
        user.setLastLoginAt(clock.now());
        ...
    }
```

放在 `SessionUser.signIn(...)` **之前**：换 id 只动 id 不动属性，顺序对属性无影响，但"先换再写"让 AUTH_USER 一定落在新会话上，阅读时不需推理。

### 2.4 变更 3：`SecurityConfig` 关闭自动换发 + 双侧 Null 仓库

文件 `server/src/main/java/com/pmo/supervise/config/SecurityConfig.java:57`：

```java
    SecurityContextRepository stateless = new NullSecurityContextRepository();
    http
        ...
        .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            // 默认 changeSessionId 会在"本请求内刚认证"时换发 id；登录换发已由 establish 显式完成
            .sessionFixation(fixation -> fixation.none()))
        .securityContext(context -> context.securityContextRepository(stateless))
        ...
    // 共享仓库决定 SessionManagementFilter 用哪个仓库判断 containsContext / 存上下文；
    // securityContext(...) 只决定载入侧。两处都给 Null，会话里才永远不会出现 SPRING_SECURITY_CONTEXT。
    http.setSharedObject(SecurityContextRepository.class, stateless);
    return http.build();
```

新增 import：`org.springframework.security.web.context.NullSecurityContextRepository`、`org.springframework.security.web.context.SecurityContextRepository`。`setSharedObject` 必须在 `http.build()` **之前**：`SessionManagementConfigurer.init` 在 build 内执行，取不到共享对象就会新建 HttpSession 版。

### 2.5 为什么两处都要（6.3.4 字节码事实，非推测）

- `SessionManagementConfigurer.init(H)`：先 `http.getSharedObject(SecurityContextRepository.class)`；非空 → 直接作为字段 `sessionManagementSecurityContextRepository`；为空且非 stateless → 新建 `HttpSessionSecurityContextRepository` 并包成 `DelegatingSecurityContextRepository` 写回共享对象。
- `SessionManagementConfigurer.configure(H)`：用上一步的字段构造 `new SessionManagementFilter(repo, strategy)`。
- `SecurityContextConfigurer.configure(H)`：**只读自己的字段** `getSecurityContextRepository()`（默认 HttpSession 版），既不读共享对象也没有 `init`。

→ 只写 `.securityContext(...)` 时 `SessionManagementFilter` 仍用 HttpSession 仓库；只写共享对象时载入侧仍读会话。**缺一不可。**

同时说明"主修复"与"防回归"的层次：真正止住换发的是 `sessionFixation().none()`（因为 6.3 默认 `requireExplicitSave=true`，今天根本没有上下文被写进会话，`containsContext` 恒 false 才导致策略每请求都跑）；Null 仓库保证"将来谁把 `requireExplicitSave` 打开也不会重新往会话写上下文、也不会让陈旧上下文盖过 `AUTH_USER`"。

### 2.6 一次登录的完整时序

1. `GET /api/auth/dingtalk/authorize-url` → 匿名会话 **S0**（`DingTalkStateStore.issue` 写 `DINGTALK_OAUTH_STATE`）+ `XSRF-TOKEN`；
2. `POST /api/auth/login`（带 `X-XSRF-TOKEN`、`SUPERVISE_SESSION=S0`）→ `establish`：`changeSessionId()` → **S1（S1≠S0）**，写 `AUTH_USER`、按需 `AUTH_MUST_CHANGE_PASSWORD`、`last_login_at`、审计 `LOGIN_SUCCESS` → 200 + `Set-Cookie: SUPERVISE_SESSION=S1`（全程唯一一次）；
3. `GET /api/auth/me` ×N（含并发）→ `SessionAuthenticationFilter` 从 `AUTH_USER` 还原 → holder 有认证 → `SessionManagementFilter.containsContext` 仍 false，但策略已是 Null → **不动 id、不写上下文、不下发会话 Cookie** → 200；
4. 角色变化后的 `me` → `AuthSession.signIn(request, fresh)` 只覆盖 `AUTH_USER`，id 不变；
5. `POST /api/auth/logout` → `AuthSession.signOut` remove + invalidate → 204，旧 Cookie 再请求 = 401。

## 3. 数据模型与存储

- **零 DDL、零迁移**：不新增表/列/索引，`V1–V6` 校验和不变。
- 会话内属性（服务端内存）：`AUTH_USER`（`Actor`）、`AUTH_MUST_CHANGE_PASSWORD`（Boolean，可缺省）、`DINGTALK_OAUTH_STATE`（`Pending`，5 分钟单次消费）。修复后**不再出现** `SPRING_SECURITY_CONTEXT`。
- Cookie：`SUPERVISE_SESSION`（HttpOnly、SameSite=Lax、`server.servlet.session.timeout=7d` 不变）与 `XSRF-TOKEN`（可读、双提交）。属性一个都不改。
- 旧会话兼容：buggy 版本创建的会话里本来就没有 `SPRING_SECURITY_CONTEXT`（没人 save），所以不存在"陈旧上下文"要清；唯一后果是**被换掉的旧 id 已失效**，持旧 Cookie 的用户会收到一次 401 后重新登录。**不需要强制全员重登，也不需要清会话。**

## 4. 接口契约（全部不变）

| 方法 | 路径 | 入参 | 返回 | 会话/Cookie 行为 |
|---|---|---|---|---|
| GET | `/api/auth/config` | — | `AuthConfig` | 不变更会话；下发/刷新 `XSRF-TOKEN` |
| POST | `/api/auth/login` | `LoginRequest{username,password}` + `X-XSRF-TOKEN` | 200 `AuthUser` | **唯一**下发新 `SUPERVISE_SESSION`；有 S0 时 S1≠S0 |
| POST | `/api/auth/logout` | `X-XSRF-TOKEN` | 204 | 会话 invalidate |
| GET | `/api/auth/me` | Cookie | 200 `AuthUser` / 401 `AUTH_REQUIRED` | 不下发 `SUPERVISE_SESSION` |
| POST | `/api/auth/password` | `ChangePasswordRequest` | 204 / 409 `AUTH_MUST_CHANGE_PASSWORD` | id 不变 |
| GET | `/api/auth/dingtalk/authorize-url` | `next` | 200 `{url}` | 可创建匿名会话（state） |
| GET | `/api/auth/dingtalk/callback` | `code,state` | 302 | 走 `establish`，同登录换发 |
| POST | `/api/auth/dingtalk/auto` | `{authCode}` | 200 `AuthUser` | 同上 |

不变项逐条：401 body 仍是 `ApiError{code:"AUTH_REQUIRED", message, details:[], context:{}, traceId}`；无 Cookie / 伪造 id → 401 且**不**下发会话 Cookie；CSRF 缺失 → 403；`SessionUser.safeNext` 仅站内；权限注解与审计动作名不变。

## 5. 前端

- **生产代码零改动**：`web/src/stores/auth.ts:44` 已是 `Promise.allSettled([authApi.config(), authApi.me()])` 并发，`web/src/api/http.ts` 的 401 拦截与跳转不动。不加"失败重试 / 串行化"——那只是把竞态藏起来（反面目标 1）。
- 新增 `web/tests/auth-ensure-loaded-concurrency.spec.ts`：mock `@/api/auth`，用两个未决 Promise 断言 `useAuthStore().ensureLoaded()` 在任一 resolve 之前**已经同时发起** `config` 与 `me`（防以后被改成串行或合并成一次），并断言 `me` 拒绝时不抛错、`loaded===true`。这是 AC-3 的前端侧防回归，不替代后端真实并发证据。

## 6. 关键实现点与边界

1. `changeSessionId()` 前必须 `getSession(false) != null`，否则 `IllegalStateException` → 500。
2. `changeSessionId` 保留全部属性：`AUTH_USER` / `AUTH_MUST_CHANGE_PASSWORD` / 钉钉 state 都还在，登录后必须仍能读到。
3. 只有 `establish` 换：`me` 的角色刷新走 `AuthSession.signIn`（不换）、改密不换、`must_change_password` 409 不换。
4. 并发窗口只剩登录那一个响应：登录是单请求，不存在"并发换发"；AC-2 的 400 次并发全部发生在换发完成之后。
5. `XSRF-TOKEN` 每请求刷新是**现状**（`CsrfConfigurer` 把 `CsrfAuthenticationStrategy` 包进会话策略，`containsContext` 恒 false 所以每次都跑），本次不改。双提交 Cookie 无服务端状态，同一请求内 cookie/header 同源，不会因并发刷新而 403——AC-7 用"并发 GET 后首个写请求直接 2xx"钉死。
6. 身份读取入口不变：`ActorArgumentResolver` → `AuthSession.current(request)`（request 属性 → holder → session 属性）。
7. 登出仍 `invalidate()`；不引入 `SecurityContextLogoutHandler`（没有 Spring 上下文可清）。
8. 单实例内存会话，不引入 Spring Session / Redis；多实例粘性会话不在本次范围（PRD 未要求）。
9. 审计不新增动作名：登录/失败/登出/改密沿用现有常量，避免 AC-9 口径漂移。
10. 禁止把身份判断改成读 `SecurityContextHolder`（每请求清空），也禁止把 `AUTH_USER` 迁到 `SPRING_SECURITY_CONTEXT`。

## 7. 测试策略

### 7.1 新增真容器并发用例

`server/src/test/java/com/pmo/supervise/auth/SessionRotationRaceTest.java`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "supervise.dingtalk.corp-id=test-corp",
        "supervise.dingtalk.agent-id=1001",
        "supervise.dingtalk.app-key=test-key",
        "supervise.dingtalk.app-secret=test-secret"})
class SessionRotationRaceTest extends IntegrationTestSupport { ... }
```

继承 `IntegrationTestSupport`（复用 MySQL 清理、`seedUser` 与固定口令）。**禁止 `MockMvc`**：`MockHttpSession` 不模拟"旧 id 失效"，缺陷存在时也会全绿。HTTP 用 `java.net.http.HttpClient` + 手动 Cookie 串（不用 `CookieManager`，避免并发共享状态）：

```java
record Resp(int status, String body, List<String> setCookies) {}
Resp get(String path, String cookieHeader);
Resp post(String path, String cookieHeader, String json, String xsrfToken);
static String sessionCookie(List<String> setCookies);   // 逐条 Set-Cookie 里解析 SUPERVISE_SESSION 次数与值
static String xsrfCookie(List<String> setCookies);
```

用例步骤（顺序即断言）：

| # | 动作 | 断言 |
|---|---|---|
| 1 | `GET /api/auth/dingtalk/authorize-url?next=/` | 200；拿到 S0 与 `XSRF-TOKEN` |
| 2 | `POST /api/auth/login`（S0 + XSRF） | 200；`Set-Cookie` 中 `SUPERVISE_SESSION=` 恰好出现 1 次且 **S1≠S0**（AC-4） |
| 3 | 只用 S1 顺序 `GET /api/auth/me` ×10 | 全 200；响应头**无** `SUPERVISE_SESSION`；第 10 次仍 200（AC-1） |
| 4 | 5 并发 `GET /api/auth/me`（S1） | 全 200、`id==adminId`、0 个会话 Cookie（AC-2 前半、AC-3 后端侧） |
| 5 | 20 轮 × 20 并发（`ExecutorService` + `CountDownLatch` 同时放行） | 400/400 = 200，0 个 401，0 个会话 Cookie（AC-2） |
| 6 | `seedUser("race-owner","竞态负责人",Role.OWNER,"ACTIVE")` 登录 → `jdbc` 改角色为 `EXEC` → `GET /me` | 200 且 `role==EXEC`、`id` 不变、无新会话 Cookie（AC-6） |
| 7 | 无 Cookie `GET /me`；伪造 `SUPERVISE_SESSION=AAAAAAAA…` | 均 401 `AUTH_REQUIRED`，响应无会话 Cookie（AC-5） |
| 8 | 并发 `GET /api/auth/config` ×5 取最新 XSRF → 立即带 token `POST /api/auth/logout` → 旧 S1 `GET /me` | 204（首个写请求直接成功，AC-7）；随后 401（AC-5） |

**修复前红 / 修复后绿取证**：

```bash
git stash push -- server/src/main
cd server && ./mvnw -q -Dtest=SessionRotationRaceTest test    # 预期失败：第 2 或第 3 步
git stash pop
cd server && ./mvnw -q -Dtest=SessionRotationRaceTest test    # 预期通过
```

### 7.2 探针脚本（原地增强，不重建）

`logs/teamflow/tf-mwaaqll7-nadfak/scripts/session-rotation-probe.py` 已有 anon-me→presession→login→burst→sequential 五阶段及 `--assert`/`--json`/`--dump-headers`。只加三件事：

- `burst` 支持 `--rounds N --concurrency M`（默认 20/20），汇总 `total / ok / 401 / other / set_cookie_count`；
- 每阶段统计响应头中 `SUPERVISE_SESSION=` 的出现次数（`rotation_count`）；
- `--assert` 增补：`login.rotation_count==1`、`burst.rotation_count==0`、`sequential.rotation_count==0`、`burst.401==0`。

### 7.3 真机证据（AC-9）

跑起真实端口（现场口径 18081 / 修复后同构端口），探针 `--json` 落 `logs/teamflow/tf-mwaaqll7-nadfak/evidence/{before,after}.json`，外加 `curl -i` 原文（登录、me×2、登出）。对照表只列 6 项：会话 TTL、Cookie 属性、401 body、权限、审计动作名、错误码集合——全部"无变化"，唯一变化是响应头 `SUPERVISE_SESSION` 的出现次数。

### 7.4 回归门禁

```bash
cd server && ./mvnw -q test                  # 247 例不减、不转红（+新增用例）
cd server && ./mvnw -q -DskipTests package
cd web && pnpm test                          # 174 例不减（+新增 spec）
cd web && pnpm build                         # vue-tsc --noEmit + vite build
bash scripts/smoke-api.sh                    # 起后端后
```

## 8. 任务拆分

| 任务 | 文件（所有权） | 依赖 | 验收 |
|---|---|---|---|
| T01 会话轮换修复 | `server/src/main/java/com/pmo/supervise/common/AuthSession.java`、`server/src/main/java/com/pmo/supervise/auth/AuthService.java`、`server/src/main/java/com/pmo/supervise/config/SecurityConfig.java` | — | 编译过；探针 sequential / burst 的 `rotation_count==0` |
| T02 真容器并发用例 | `server/src/test/java/com/pmo/supervise/auth/SessionRotationRaceTest.java` | 读 T01 三文件 | 修复前红、修复后全绿 |
| T03 探针增强 + 真机证据 | `logs/teamflow/tf-mwaaqll7-nadfak/scripts/session-rotation-probe.py`、`logs/teamflow/tf-mwaaqll7-nadfak/evidence/*` | T01 | `--assert` 退出码 0；before/after 对照表 |
| T04 前端防回归 spec | `web/tests/auth-ensure-loaded-concurrency.spec.ts` | — | `pnpm test` 通过 |
| T05 交付自检 | `.gitignore`、`docs/teamflow/memory.md` | T01–T04 | 全量回归绿；`git status` 只含修复源码+测试+任务夹；单次提交 main |

T01 与 T02/T04 文件不相交可并行；T03 依赖 T01 产物；T05 最后串行。T02 必须按 §2.2 调用 `AuthSession.rotateSessionId`，不得自造 `rotate()` / `refreshSession()` / `renewSession()` 等名字（首调即 500/编译失败）。T05 的 `.gitignore` 只补 `.demo-shots/` 一行（现仅存在于 `.git/info/exclude`），不动其它规则；`docs/teamflow/memory.md` 按"替换同主题行"更新，不追加流水账。

## 9. AC → 验证映射

| AC | 验证 |
|---|---|
| AC-1 | `SessionRotationRaceTest` 步骤 3；探针 sequential `rotation_count==0` |
| AC-2 | 步骤 4/5（5 并发、20×20=400）；探针 burst `401==0` |
| AC-3 | 步骤 4（真实并发）+ `auth-ensure-loaded-concurrency.spec.ts`；Chromium 可跑则加跑 Playwright，不可跑则在报告写明"以 HTTP 并发 + 人工复测为准" |
| AC-4 | 步骤 2（S1≠S0）；无会话直接登录也 200 |
| AC-5 | 步骤 7/8 + 现有 `auth/AuthApiTest` 断言 |
| AC-6 | 步骤 6 + `AuthApiTest` 改密 / 409 |
| AC-7 | 步骤 8 + `scripts/smoke-api.sh` |
| AC-8 | `./mvnw -q test`（247+新增）、`pnpm test`（174+1）、`pnpm build` |
| AC-9 | 探针 before/after JSON + `curl -i` 原文，6 项口径对照表 |

## 10. 风险与回滚

- **R1** `http.setSharedObject` 若与后续新增 configurer 语义冲突：先只保留 `sessionFixation(none())`（主修复）重跑用例；用例仍绿则按需回退 Null 仓库，并在 QA 报告中写明取舍。
- **R2** 真容器用例依赖本地 MySQL 与随机端口——与现有 247 例同环境，不新增依赖；20×20 共 400 请求，本机 < 20s。
- **R3** `Set-Cookie` 头可能被容器合并：断言"逐条 Set-Cookie 中 `SUPERVISE_SESSION=` 出现次数"，而不是"响应头个数"。
- **回滚**：三处改动互相独立，`git revert` 单个提交即可；无数据变更，无需回滚脚本。
