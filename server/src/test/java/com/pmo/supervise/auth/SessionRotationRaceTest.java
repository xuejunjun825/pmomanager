package com.pmo.supervise.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话轮换竞态回归（r5 · AC-1/AC-2/AC-4/AC-5/AC-6/AC-7）。
 *
 * <p><b>为什么不是 MockMvc</b>：{@code MockHttpSession} 不模拟"旧 id 失效"——缺陷存在时
 * MockMvc 顺序用例照样全绿。这里的每一步都走真实 Servlet 容器（{@code RANDOM_PORT}）与真实
 * 并发线程，Cookie 用字符串手工拼装（不用 {@code CookieManager}），否则测试自身的并发 Cookie
 * 状态会掩盖被测量的事实。</p>
 *
 * <p>覆盖：登录恰好一次换发且前后 id 不同（AC-4）→ 顺序 10 次与 5 并发与 20×20=400 全部 200
 * 且不再下发会话 Cookie（AC-1/AC-2）→ 角色刷新 id 不变（AC-6）→ 匿名/伪造会话 401 且不建会话
 * （AC-5）→ 并发刷 token 后首个写请求直接 204、登出后 401（AC-7/AC-5）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "supervise.dingtalk.corp-id=test-corp",
        "supervise.dingtalk.agent-id=1001",
        "supervise.dingtalk.app-key=test-key",
        "supervise.dingtalk.app-secret=test-secret"})
class SessionRotationRaceTest extends IntegrationTestSupport {

    private static final String SESSION_COOKIE = "SUPERVISE_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String SEED_PASSWORD = "Admin@12345";
    private static final int PARALLEL = 20;
    private static final int ROUNDS = 20;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 并发固定线程池：一次性建好，400 次请求不重复造线程。 */
    private final ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    record Resp(int status, String body, List<String> setCookies) {
    }

    @Test
    void sessionRotatesOnlyAtLoginAndStaysStableUnderConcurrency() throws Exception {
        // ── 1) 匿名会话与 CSRF（AC-4 前置条件）────────────────────────────────
        Resp config = get("/api/auth/config", null);
        assertThat(config.status()).isEqualTo(200);
        String csrf = xsrfCookie(config.setCookies());
        assertThat(csrf).as("CSRF Cookie 必须下发").isNotBlank();

        Resp presession = get("/api/auth/dingtalk/authorize-url?next=%2F", cookie(null, csrf));
        assertThat(presession.status()).isEqualTo(200);
        String s0 = sessionCookie(presession.setCookies());
        assertThat(s0).as("钉钉 state 写入会话，登录前应有匿名会话 id").isNotBlank();

        // ── 2) 登录：恰好换发一次，S1 != S0（AC-4）───────────────────────────
        Resp login = post("/api/auth/login", cookie(s0, csrf),
                json.writeValueAsString(Map.of("username", "admin", "password", SEED_PASSWORD)), csrf);
        assertThat(login.status()).isEqualTo(200);
        List<String> rotated = sessionCookies(login.setCookies());
        assertThat(rotated).as("登录响应必须带且只带一个新会话 id").hasSize(1);
        String s1 = rotated.get(0);
        assertThat(s1).as("会话固定防护：登录前后 id 必须不同").isNotEqualTo(s0);

        // ── 3) 顺序 10 次：不再换发（AC-1）────────────────────────────────────
        for (int i = 1; i <= 10; i++) {
            Resp me = get("/api/auth/me", cookie(s1, csrf));
            assertThat(me.status()).as("第 %d 次顺序请求", i).isEqualTo(200);
            assertThat(sessionCookies(me.setCookies())).as("第 %d 次顺序请求不得换发会话", i).isEmpty();
            assertThat(json.readTree(me.body()).get("id").asLong()).isEqualTo(adminId);
        }

        // ── 4) 5 并发（AC-2 前半 / AC-3 后端侧）──────────────────────────────
        List<Resp> burst = concurrent(5, () -> get("/api/auth/me", cookie(s1, csrf)));
        assertThat(burst).hasSize(5);
        for (Resp me : burst) {
            assertThat(me.status()).isEqualTo(200);
            assertThat(sessionCookies(me.setCookies())).isEmpty();
            assertThat(json.readTree(me.body()).get("id").asLong()).isEqualTo(adminId);
        }

        // ── 5) 20 轮 × 20 并发 = 400 次（AC-2）───────────────────────────────
        int ok = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            List<Resp> responses = concurrent(PARALLEL, () -> get("/api/auth/me", cookie(s1, csrf)));
            assertThat(responses).hasSize(PARALLEL);
            for (Resp me : responses) {
                assertThat(me.status()).as("第 %d 轮并发认证", round).isEqualTo(200);
                assertThat(sessionCookies(me.setCookies()))
                        .as("第 %d 轮并发认证不得换发会话", round).isEmpty();
                ok++;
            }
        }
        assertThat(ok).isEqualTo(ROUNDS * PARALLEL);

        // ── 6) 角色刷新不换 id（AC-6）────────────────────────────────────────
        long ownerId = seedOwner("race-owner", "竞态负责人");
        Resp ownerLogin = post("/api/auth/login", cookie(null, csrf),
                json.writeValueAsString(Map.of("username", "race-owner", "password", SEED_PASSWORD)), csrf);
        assertThat(ownerLogin.status()).isEqualTo(200);
        String ownerSession = sessionCookie(ownerLogin.setCookies());
        assertThat(ownerSession).isNotBlank();

        jdbc.update("UPDATE users SET role = 'EXEC' WHERE id = ?", ownerId);
        Resp ownerMe = get("/api/auth/me", cookie(ownerSession, csrf));
        assertThat(ownerMe.status()).isEqualTo(200);
        JsonNode owner = json.readTree(ownerMe.body());
        assertThat(owner.get("role").asText()).as("me() 必须回库刷新角色").isEqualTo("EXEC");
        assertThat(owner.get("id").asLong()).isEqualTo(ownerId);
        assertThat(sessionCookies(ownerMe.setCookies())).as("角色刷新不得换发会话").isEmpty();

        // ── 7) 401 语义：匿名与伪造会话都不建会话（AC-5）─────────────────────
        assertUnauthorized(get("/api/auth/me", null));
        assertUnauthorized(get("/api/auth/me", SESSION_COOKIE + "=" + "A".repeat(32)));

        // ── 8) CSRF 写请求与登出（AC-7 / AC-5）───────────────────────────────
        // CookieCsrfTokenRepository 在 Cookie 里的 token 仍有效时不会另发新 token，因此并发 GET
        // 之后沿用同一个明文 token 即可；AC-7 要钉的是"并发 GET 不会让首个写请求 403"。
        List<Resp> configs = concurrent(5, () -> get("/api/auth/config", cookie(s1, csrf)));
        for (Resp cfg : configs) {
            assertThat(cfg.status()).isEqualTo(200);
        }
        String freshXsrf = csrf;
        assertThat(freshXsrf).as("并发 GET 后仍有可用的 CSRF token").isNotBlank();

        Resp logout = post("/api/auth/logout", cookie(s1, freshXsrf), "", freshXsrf);
        assertThat(logout.status()).as("并发刷 token 后首个写请求必须直接通过").isEqualTo(204);
        assertUnauthorized(get("/api/auth/me", cookie(s1, freshXsrf)));
    }

    // ── HTTP 脚手架（手工 Cookie 串，绝不共享 CookieManager）─────────────────

    private void assertUnauthorized(Resp resp) throws Exception {
        assertThat(resp.status()).isEqualTo(401);
        assertThat(json.readTree(resp.body()).get("code").asText()).isEqualTo("AUTH_REQUIRED");
        assertThat(sessionCookies(resp.setCookies())).as("401 不得下发会话 Cookie").isEmpty();
    }

    private Resp get(String path, String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }
        return send(builder.build());
    }

    private Resp post(String path, String cookieHeader, String body, String xsrf) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (cookieHeader != null) {
            builder.header("Cookie", cookieHeader);
        }
        if (xsrf != null) {
            builder.header("X-XSRF-TOKEN", xsrf);
        }
        return send(builder.build());
    }

    private Resp send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body(), response.headers().allValues("set-cookie"));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** 同时放行 n 个请求：CountDownLatch 保证真的并发，而不是排队。 */
    private List<Resp> concurrent(int count, Callable<Resp> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(30, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Resp> responses = new ArrayList<>(count);
        for (Future<Resp> future : futures) {
            responses.add(future.get(120, TimeUnit.SECONDS));
        }
        return responses;
    }

    private static String cookie(String session, String xsrf) {
        List<String> parts = new ArrayList<>(2);
        if (session != null) {
            parts.add(SESSION_COOKIE + "=" + session);
        }
        if (xsrf != null) {
            parts.add(CSRF_COOKIE + "=" + xsrf);
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /** Set-Cookie 里 SUPERVISE_SESSION 的全部取值（顺序即出现顺序）：换发次数靠它计数。 */
    static List<String> sessionCookies(List<String> setCookies) {
        return cookieValues(setCookies, SESSION_COOKIE);
    }

    /** 最后一次出现的会话 id：没有则 null。 */
    static String sessionCookie(List<String> setCookies) {
        List<String> values = sessionCookies(setCookies);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    static String xsrfCookie(List<String> setCookies) {
        List<String> values = cookieValues(setCookies, CSRF_COOKIE);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    private static List<String> cookieValues(List<String> setCookies, String name) {
        List<String> values = new ArrayList<>();
        for (String setCookie : setCookies) {
            String pair = setCookie.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(name)) {
                values.add(pair.substring(eq + 1).trim());
            }
        }
        return values;
    }
}
