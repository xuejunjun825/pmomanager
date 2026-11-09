#!/usr/bin/env bash
# 接口冒烟：健康检查 → 未登录 401 → CSRF → 密码登录 → 任务池/通知列表 → 报表设置（admin 读写 + OWNER 403）。
# 用法：BASE_URL=http://127.0.0.1:8080 SMOKE_USERNAME=admin SMOKE_PASSWORD=Admin@12345 bash scripts/smoke-api.sh
# 可选：SMOKE_OWNER_USERNAME / SMOKE_OWNER_PASSWORD 提供一个负责人账号，用于校验 GET /api/settings 越权 403；
#      未提供时该步 SKIP。
# 说明：admin 首登强制改密时，业务接口会返回 428；此时脚本报告 PARTIAL 而不改任何数据。
#      设置一节会用「读到的当前值」原样 PUT 回写（不改名单/时间），只为验证 CSRF 明文回带与 200 契约。
set -uo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
USERNAME="${SMOKE_USERNAME:-admin}"
PASSWORD="${SMOKE_PASSWORD:-Admin@12345}"
STRICT="${STRICT:-0}"

COOKIE_JAR="$(mktemp -t supervise-smoke.XXXXXX)"
OWNER_JAR="$(mktemp -t supervise-smoke-owner.XXXXXX)"
BODY_FILE="$(mktemp -t supervise-smoke-body.XXXXXX)"
trap 'rm -f "$COOKIE_JAR" "$OWNER_JAR" "$BODY_FILE"' EXIT

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
fi

echo "== 结果：PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[[ "$FAIL" -eq 0 ]] || exit 1
[[ "$STRICT" == "1" && "$SKIP" -gt 0 ]] && exit 1
exit 0
