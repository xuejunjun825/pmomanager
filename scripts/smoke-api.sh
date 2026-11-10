#!/usr/bin/env bash
# 接口冒烟：健康检查 → 未登录 401 → CSRF → 密码登录 → 任务池/通知列表 → 报表设置（admin 读写 + OWNER 403）
#          → 来源配置 → 建 NOTE 任务 → 追加进度并 @ 一人 → MENTION 通知 → 被@人权限护栏
#          → 删除被引用来源 409 → 停用来源再建任务 400。
# 用法：BASE_URL=http://127.0.0.1:8080 SMOKE_USERNAME=admin SMOKE_PASSWORD=Admin@12345 bash scripts/smoke-api.sh
# 可选：SMOKE_OWNER_USERNAME / SMOKE_OWNER_PASSWORD 提供一个负责人账号，用于校验 GET /api/settings 越权 403；
#      未提供时该步 SKIP。
#      SMOKE_MENTION_USERNAME / SMOKE_MENTION_PASSWORD 提供一个普通成员账号（默认回退到 SMOKE_OWNER_*），
#      用于校验 @ 通知、被@人详情 200 / 状态动作 403 / 续写进度；未提供时该组 SKIP。
# 说明：来源/进度链路依赖 python3 解析 JSON；每轮会新建一个唯一名称的自定义来源（结束后停用），
#      因此请在可丢弃的冒烟库上运行（脚本会先清理上一轮未被引用的自定义来源）。
# 说明：admin 首登强制改密时，业务接口会返回 428；此时脚本报告 PARTIAL 而不改任何数据。
#      设置一节会用「读到的当前值」原样 PUT 回写（不改名单/时间），只为验证 CSRF 明文回带与 200 契约。
set -uo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
USERNAME="${SMOKE_USERNAME:-admin}"
PASSWORD="${SMOKE_PASSWORD:-Admin@12345}"
STRICT="${STRICT:-0}"

COOKIE_JAR="$(mktemp -t supervise-smoke.XXXXXX)"
OWNER_JAR="$(mktemp -t supervise-smoke-owner.XXXXXX)"
MENTION_JAR="$(mktemp -t supervise-smoke-mention.XXXXXX)"
BODY_FILE="$(mktemp -t supervise-smoke-body.XXXXXX)"
trap 'rm -f "$COOKIE_JAR" "$OWNER_JAR" "$MENTION_JAR" "$BODY_FILE"' EXIT

PASS=0
FAIL=0
SKIP=0

pass() { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
skip() { SKIP=$((SKIP + 1)); printf '  \033[33mSKIP\033[0m %s\n' "$1"; }

# request <method> <path> [json-body] [extra-header] [cookie-jar] → 打印状态码，body 写入 BODY_FILE
request() {
  local method="$1" path="$2" body="${3:-}" header="${4:-}" jar="${5:-$COOKIE_JAR}"
  local args=(-sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" -b "$jar" -c "$jar"
    -H 'Accept: application/json')
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  if [[ -n "$header" ]]; then
    args+=(-H "$header")
  fi
  curl "${args[@]}" "$BASE_URL$path"
}

expect_code() {
  local actual="$1" expected="$2" label="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "${label}（HTTP ${actual}）"
  else
    fail "${label}（期望 ${expected}，实际 ${actual}，响应：$(head -c 300 "$BODY_FILE")）"
  fi
}

# json_get <top-level-key> → 从最近一次响应的 BODY_FILE 取标量字段；解析失败输出空串。
json_get() {
  python3 -c 'import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    value = json.load(fh).get(sys.argv[2])
print("" if value is None else value)' "$BODY_FILE" "$1" 2>/dev/null
}

# json_array_len <top-level-key> → 数组长度（缺失按 0）。
json_array_len() {
  python3 -c 'import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    value = json.load(fh).get(sys.argv[2]) or []
print(len(value))' "$BODY_FILE" "$1" 2>/dev/null
}

# csrf_of <cookie-jar> → 取当前会话的 CSRF 明文。
# 登录/首次 /api/auth/me 会轮换 session 并清空 XSRF-TOKEN cookie（Spring 防会话固定），
# 此时补一次 GET /api/auth/config 让服务端重新落 cookie，行为与前端启动引导一致。
csrf_of() {
  local jar="$1" token
  token=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$jar" | tail -1)
  if [[ -z "$token" ]]; then
    request GET /api/auth/config "" "" "$jar" >/dev/null
    token=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$jar" | tail -1)
  fi
  printf '%s' "$token"
}

echo "== 会议任务督办系统 · API 冒烟（${BASE_URL}）=="

echo "-- 1. 健康检查"
code=$(request GET /actuator/health)
if [[ "$code" == "200" ]] && grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' "$BODY_FILE"; then
  pass "GET /actuator/health 返回 UP"
else
  fail "GET /actuator/health（HTTP ${code}，响应：$(head -c 300 "$BODY_FILE")）"
fi

echo "-- 2. 未登录访问必须 401"
code=$(request GET '/api/tasks?page=1&size=5')
expect_code "$code" "401" "GET /api/tasks 未登录被拒"
if [[ "$code" == "401" ]] && ! grep -q '"code"[[:space:]]*:[[:space:]]*"AUTH_REQUIRED"' "$BODY_FILE"; then
  fail "401 响应缺少 AUTH_REQUIRED 错误体"
fi

echo "-- 3. CSRF 与登录"
code=$(request GET /api/auth/config)
if [[ "$code" == "404" ]]; then
  skip "认证接口尚未实现（GET /api/auth/config → 404），登录链路与业务列表跳过"
  echo "== 结果：PASS=${PASS} FAIL=${FAIL} SKIP=${SKIP}（PARTIAL）=="
  [[ "$STRICT" == "1" && "$SKIP" -gt 0 ]] && exit 1
  exit 0
fi
expect_code "$code" "200" "GET /api/auth/config"
if [[ "$code" != "200" ]]; then
  echo "== 结果：PASS=$PASS FAIL=$FAIL =="
  exit 1
fi

CSRF_TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -1)
if [[ -n "$CSRF_TOKEN" ]]; then
  pass "已下发 XSRF-TOKEN Cookie"
else
  fail "未收到 XSRF-TOKEN Cookie"
fi

code=$(request POST /api/auth/login "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" "X-XSRF-TOKEN: $CSRF_TOKEN")
expect_code "$code" "200" "POST /api/auth/login（${USERNAME}）"

echo "-- 4. 登录后的读接口"
code=$(request GET '/api/tasks?page=1&size=5')
if [[ "$code" == "428" ]] || grep -q '"code"[[:space:]]*:[[:space:]]*"PASSWORD_CHANGE_REQUIRED"' "$BODY_FILE"; then
  skip "admin 首登未改密（428），业务读接口跳过；请先改密再复跑"
else
  expect_code "$code" "200" "GET /api/tasks"
  if [[ "$code" == "200" ]]; then
    for field in '"items"' '"total"' '"page"' '"size"'; do
      grep -q "$field" "$BODY_FILE" || fail "任务分页响应缺少字段 $field"
    done
  fi

  code=$(request GET '/api/notifications?page=1&size=5')
  expect_code "$code" "200" "GET /api/notifications"

  code=$(request GET '/api/tasks/999999')
  expect_code "$code" "404" "GET /api/tasks/999999 不存在返回 404"

  echo "-- 5. 报表设置（admin 读写 + OWNER 越权）"
  code=$(request GET /api/settings)
  if [[ "$code" == "200" ]]; then
    for field in '"report"' '"recipientsEmpty"' '"dingtalk"'; do
      grep -q "$field" "$BODY_FILE" || fail "设置响应缺少字段 $field"
    done
    if command -v python3 >/dev/null 2>&1; then
      # 用读到的当前值构造回写体：保留原开关、时间与收件人 id，避免冒烟顺手改数据
      PAYLOAD=$(python3 - "$BODY_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as fh:
    view = json.load(fh)
report = view.get('report') or {}
print(json.dumps({
    'dailyEnabled': bool(report.get('dailyEnabled')),
    'dailyTime': report.get('dailyTime') or '',
    'weeklyEnabled': bool(report.get('weeklyEnabled')),
    'weeklyTime': report.get('weeklyTime') or '',
    'recipientIds': [row['id'] for row in (report.get('recipients') or [])],
}, ensure_ascii=False))
PY
)
      if [[ -n "$PAYLOAD" ]]; then
        # 登录会换发新的 XSRF-TOKEN，写请求必须用 jar 里的当前值（同时验证明文 CSRF 契约）
        CSRF_TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -1)
        code=$(request PUT /api/settings/report "$PAYLOAD" "X-XSRF-TOKEN: $CSRF_TOKEN")
        expect_code "$code" "200" "PUT /api/settings/report（原值回写）"
      else
        fail "无法从 GET /api/settings 生成回写请求体"
      fi
    else
      skip "未找到 python3，PUT /api/settings/report 跳过（GET 已校验）"
    fi
  else
    expect_code "$code" "200" "GET /api/settings（admin）"
  fi

  OWNER_USERNAME="${SMOKE_OWNER_USERNAME:-}"
  OWNER_PASSWORD="${SMOKE_OWNER_PASSWORD:-}"
  if [[ -z "$OWNER_USERNAME" || -z "$OWNER_PASSWORD" ]]; then
    skip "未提供 SMOKE_OWNER_USERNAME/SMOKE_OWNER_PASSWORD，OWNER 越权 403 校验跳过"
  else
    request GET /api/auth/config "" "" "$OWNER_JAR" >/dev/null
    OWNER_TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$OWNER_JAR" | tail -1)
    code=$(request POST /api/auth/login "{\"username\":\"$OWNER_USERNAME\",\"password\":\"$OWNER_PASSWORD\"}" "X-XSRF-TOKEN: $OWNER_TOKEN" "$OWNER_JAR")
    if [[ "$code" != "200" ]]; then
      skip "负责人账号登录失败（HTTP ${code}），越权 403 校验跳过"
    else
      code=$(request GET /api/settings "" "" "$OWNER_JAR")
      if [[ "$code" == "428" ]] || grep -q '"code"[[:space:]]*:[[:space:]]*"PASSWORD_CHANGE_REQUIRED"' "$BODY_FILE"; then
        skip "负责人账号首登未改密（428），越权 403 校验跳过"
      else
        expect_code "$code" "403" "GET /api/settings（OWNER 越权返回 403）"
      fi
    fi
  fi

  echo "-- 6. 来源配置 → 建任务 → 进度 @ → 通知 → 权限护栏（r2 能力）"
  if ! command -v python3 >/dev/null 2>&1; then
    skip "未找到 python3，来源配置与进度冒烟跳过"
  else
    code=$(request GET /api/source-types)
    if [[ "$code" != "200" ]]; then
      fail "GET /api/source-types（HTTP ${code}，响应：$(head -c 300 "$BODY_FILE")）"
    else
      # 锁定来源 MEETING 必须存在且不可停用/删除；PROJECT/DAILY 为迁移种子，
      # 但按设计允许管理员删除（仅 MEETING 锁定），缺失时只提示不判失败。
      if grep -q '"code":"MEETING"' "$BODY_FILE" \
        && grep -q '"locked":true' "$BODY_FILE"; then
        pass "GET /api/source-types 返回锁定的内置会议来源"
      else
        fail "GET /api/source-types 缺少锁定来源 MEETING"
      fi
      for seeded in PROJECT DAILY; do
        grep -q "\"code\":\"$seeded\"" "$BODY_FILE" \
          || skip "迁移种子 $seeded 已被管理员删除（仅 MEETING 受保护，符合设计）"
      done

      # 只清理本脚本上一轮创建且已无引用的来源（名称前缀「冒烟」），
      # 不触碰迁移种子或他人数据，避免多轮累计撞 20 上限。
      STALE_SOURCES=$(python3 - "$BODY_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as fh:
    rows = json.load(fh)
print(' '.join(row['code'] for row in rows
               if not row.get('builtin')
               and str(row.get('name', '')).startswith('冒烟')
               and row.get('usageCount', 0) == 0))
PY
)
      for stale in $STALE_SOURCES; do
        CSRF_TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -1)
        request DELETE "/api/source-types/$stale" "" "X-XSRF-TOKEN: $CSRF_TOKEN" >/dev/null
      done

      CSRF_TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -1)
      SRC_NAME="冒烟$(date +%H%M%S)"
      code=$(request POST /api/source-types "{\"name\":\"$SRC_NAME\",\"form\":\"NOTE\",\"color\":\"#067647\"}" "X-XSRF-TOKEN: $CSRF_TOKEN")
      expect_code "$code" "201" "POST /api/source-types（${SRC_NAME}）"
      SRC_CODE=$(json_get code)
      if [[ -z "$SRC_CODE" ]]; then
        fail "创建来源后未拿到 code，来源/进度链路跳过"
      else
        request GET /api/auth/me >/dev/null
        ADMIN_ID=$(json_get id)
        DUE_DATE=$(python3 -c 'import datetime; print((datetime.date.today() + datetime.timedelta(days=14)).isoformat())')
        TASK_TITLE="冒烟进度任务$(date +%H%M%S)"
        code=$(request POST /api/tasks "{\"title\":\"$TASK_TITLE\",\"ownerId\":$ADMIN_ID,\"dueDate\":\"$DUE_DATE\",\"sourceType\":\"$SRC_CODE\",\"sourceNote\":\"冒烟来源说明\",\"priority\":\"MEDIUM\"}" "X-XSRF-TOKEN: $CSRF_TOKEN")
        expect_code "$code" "201" "POST /api/tasks（自定义来源 ${SRC_CODE}，NOTE 形态）"
        TASK_ID=$(json_get id)
        if [[ -z "$TASK_ID" ]]; then
          fail "建任务后未拿到 id，进度/通知链路跳过"
        else
          code=$(request POST "/api/tasks/$TASK_ID/progress" '{"content":"冒烟进度一：自定义来源可建任务"}' "X-XSRF-TOKEN: $CSRF_TOKEN")
          expect_code "$code" "201" "POST /api/tasks/{id}/progress（第一条，无 @）"

          M_USERNAME="${SMOKE_MENTION_USERNAME:-${SMOKE_OWNER_USERNAME:-}}"
          M_PASSWORD="${SMOKE_MENTION_PASSWORD:-${SMOKE_OWNER_PASSWORD:-}}"
          if [[ -z "$M_USERNAME" || -z "$M_PASSWORD" ]]; then
            skip "未提供 SMOKE_MENTION_USERNAME/PASSWORD，@ 通知与被@人权限校验跳过"
          else
            request GET /api/auth/config "" "" "$MENTION_JAR" >/dev/null
            M_TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$MENTION_JAR" | tail -1)
            code=$(request POST /api/auth/login "{\"username\":\"$M_USERNAME\",\"password\":\"$M_PASSWORD\"}" "X-XSRF-TOKEN: $M_TOKEN" "$MENTION_JAR")
            if [[ "$code" != "200" ]]; then
              skip "被@人账号登录失败（HTTP ${code}），@ 链路跳过"
            else
              request GET /api/auth/me "" "" "$MENTION_JAR" >/dev/null
              MENTION_ID=$(json_get id)
              # 登录会换发新的 XSRF-TOKEN，后续写请求必须用 jar 里的当前值
              M_TOKEN=$(csrf_of "$MENTION_JAR")
              code=$(request POST "/api/tasks/$TASK_ID/progress" "{\"content\":\"冒烟进度二：请 @ 关注\",\"mentionedUserIds\":[$MENTION_ID]}" "X-XSRF-TOKEN: $CSRF_TOKEN")
              expect_code "$code" "201" "POST /api/tasks/{id}/progress（第二条，@ 一人）"
              PROGRESS_ID=$(json_get id)
              if [[ "$(json_array_len mentions)" == "1" ]]; then
                pass "进度响应带 1 个被@人（id=${MENTION_ID}）"
              else
                fail "进度响应 mentions 数量异常（$(json_array_len mentions)）"
              fi

              code=$(request GET '/api/notifications?type=MENTION&page=1&size=20' "" "" "$MENTION_JAR")
              expect_code "$code" "200" "GET /api/notifications?type=MENTION（被@人）"
              if [[ -n "$PROGRESS_ID" ]] && python3 - "$BODY_FILE" "$PROGRESS_ID" <<'PY'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as fh:
    page = json.load(fh)
progress_id = int(sys.argv[2])
raise SystemExit(0 if any(item.get('progressId') == progress_id
                          for item in (page.get('items') or [])) else 1)
PY
              then
                pass "MENTION 通知命中进度 ${PROGRESS_ID}（progressId 深链可用）"
              else
                fail "MENTION 通知未命中进度 ${PROGRESS_ID}（响应：$(head -c 300 "$BODY_FILE")）"
              fi

              code=$(request GET "/api/tasks/$TASK_ID" "" "" "$MENTION_JAR")
              expect_code "$code" "200" "被@人 GET /api/tasks/{id}（只读可见）"
              if [[ "$code" == "200" ]]; then
                grep -q '"mentionOnly"[[:space:]]*:[[:space:]]*true' "$BODY_FILE" \
                  && pass "被@人视角 mentionOnly=true（页面整组隐藏状态动作）" \
                  || fail "被@人视角缺少 mentionOnly=true"
                grep -q '"canAddProgress"[[:space:]]*:[[:space:]]*true' "$BODY_FILE" \
                  && pass "被@人视角 canAddProgress=true（保留续写入口）" \
                  || fail "被@人视角缺少 canAddProgress=true"
              fi

              code=$(request POST "/api/tasks/$TASK_ID/progress" '{"content":"被@人续写一条进度"}' "X-XSRF-TOKEN: $M_TOKEN" "$MENTION_JAR")
              expect_code "$code" "201" "被@人可续写进度"

              M_TOKEN=$(csrf_of "$MENTION_JAR")
              code=$(request POST "/api/tasks/$TASK_ID/start" "" "X-XSRF-TOKEN: $M_TOKEN" "$MENTION_JAR")
              expect_code "$code" "403" "被@人 POST /api/tasks/{id}/start 越权 403"
            fi
          fi

          echo "-- 7. 来源护栏（被引用删除 409 / 停用后新建 400）"
          CSRF_TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -1)
          code=$(request DELETE "/api/source-types/$SRC_CODE" "" "X-XSRF-TOKEN: $CSRF_TOKEN")
          expect_code "$code" "409" "DELETE 被任务引用的来源返回 409"
          grep -q '"code":"SOURCE_IN_USE"' "$BODY_FILE" || fail "409 响应缺少 SOURCE_IN_USE 错误码"

          code=$(request PATCH "/api/source-types/$SRC_CODE/status" '{"enabled":false}' "X-XSRF-TOKEN: $CSRF_TOKEN")
          expect_code "$code" "200" "PATCH /api/source-types/{code}/status 停用来源"

          code=$(request POST /api/tasks "{\"title\":\"停用来源复建任务\",\"ownerId\":$ADMIN_ID,\"dueDate\":\"$DUE_DATE\",\"sourceType\":\"$SRC_CODE\",\"sourceNote\":\"应被拒\",\"priority\":\"MEDIUM\"}" "X-XSRF-TOKEN: $CSRF_TOKEN")
          expect_code "$code" "400" "停用来源再建任务返回 400"
          grep -q '"code":"SOURCE_TYPE_DISABLED"' "$BODY_FILE" || fail "400 响应缺少 SOURCE_TYPE_DISABLED 错误码"

          code=$(request GET "/api/tasks?sourceType=$SRC_CODE&page=1&size=5")
          expect_code "$code" "200" "停用来源的历史任务仍可筛选"
          TOTAL=$(json_get total)
          if [[ "${TOTAL:-0}" -ge 1 ]]; then
            pass "停用来源筛选到 ${TOTAL} 条历史任务"
          else
            fail "停用来源筛选结果为空（total=${TOTAL:-0}）"
          fi
        fi
      fi
    fi
  fi
fi

echo "== 结果：PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[[ "$FAIL" -eq 0 ]] || exit 1
[[ "$STRICT" == "1" && "$SKIP" -gt 0 ]] && exit 1
exit 0
