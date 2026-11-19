#!/usr/bin/env bash
# =============================================================================
# 三尺讲台本地验收栈：Docker Compose + Vite
#
# 常用：
#   ./scripts/dev.sh up              # 启动数据库 / AI / app / eval-worker + Vite
#   ./scripts/dev.sh up --build      # 源码改动后重建后端 / AI 镜像再启动
#   ./scripts/dev.sh status          # 查看容器、后端健康、前端地址
#   ./scripts/dev.sh logs app        # 跟踪某个容器日志
#   ./scripts/dev.sh down            # 停止并删除容器（保留 postgres/minio 数据卷）
#   ./scripts/dev.sh reset-demo --yes# 重建 demo 数据库（会清空 demo 数据）
#
# 说明：
#   - 自动从 server/.env.example 生成 server/.env，并补齐随机密钥；
#   - demo 数据来自 db/demo V900/V901，演示账号密码统一 Demo1234!；
#   - 前端日志在 .run/frontend.log，PID 在 .run/frontend.pid；
#   - 录制页必须用 http://localhost:5173（安全上下文），LAN IP 不能调用摄像头/屏幕共享。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
FRONTEND_LOG="$RUN_DIR/frontend.log"
FRONTEND_PID="$RUN_DIR/frontend.pid"
COMPOSE_BASE="$ROOT/docker-compose.yml"
COMPOSE_DEMO="$ROOT/scripts/compose.demo.yml"
ENV_FILE="$ROOT/server/.env"
ENV_EXAMPLE="$ROOT/server/.env.example"

export AI_SERVICE_TOKEN="${AI_SERVICE_TOKEN:-dev-service-token}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-shijiangtai_dev}"

compose() {
  docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_DEMO" "$@"
}

log() { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '\n错误：%s\n' "$*" >&2; exit 1; }

gen_secret() {
  local bytes="${1:-48}"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 "$bytes" | tr -d '\n'
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "$bytes" <<'PY'
import secrets, sys
print(secrets.token_urlsafe(int(sys.argv[1])))
PY
  else
    die "需要 openssl 或 python3 生成密钥"
  fi
}

env_value() {
  local key="$1"
  grep -E "^${key}=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2- || true
}

set_env() {
  local key="$1"
  local value="$2"
  local tmp
  tmp="$(mktemp)"
  if grep -qE "^${key}=" "$ENV_FILE" 2>/dev/null; then
    awk -v k="$key" -v v="$value" 'BEGIN{FS=OFS="="} $1==k {print k"="v; next} {print}' \
      "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"
    printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  mv "$tmp" "$ENV_FILE"
  chmod 600 "$ENV_FILE" 2>/dev/null || true
}

ensure_env() {
  if [ ! -f "$ENV_FILE" ]; then
    [ -f "$ENV_EXAMPLE" ] || die "缺少 $ENV_EXAMPLE"
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    log "已从 .env.example 生成 server/.env"
  fi

  [ -n "$(env_value M1_JWT_SECRET)" ] || set_env M1_JWT_SECRET "$(gen_secret 48)"
  [ -n "$(env_value M1_EMAIL_CODE_SECRET)" ] || set_env M1_EMAIL_CODE_SECRET "$(gen_secret 32)"
  [ -n "$(env_value M1_INTERNAL_TOKEN)" ] || set_env M1_INTERNAL_TOKEN "$(gen_secret 32)"

  # 本地验收默认走 Mock，避免没有 SMTP / 微信资质时登录、健康检查失败。
  if [ "$(env_value M1_MAIL_MOCK)" = "false" ] && [ -z "$(env_value M1_MAIL_PASSWORD)" ]; then
    set_env M1_MAIL_MOCK true
  fi
  if [ "$(env_value M1_WECHAT_MOCK)" = "false" ] && [ -z "$(env_value M1_WECHAT_APP_ID)" ]; then
    set_env M1_WECHAT_MOCK true
  fi
  chmod 600 "$ENV_FILE" 2>/dev/null || true
}

ensure_docker() {
  command -v docker >/dev/null 2>&1 || die "未找到 docker 命令"
  if docker info >/dev/null 2>&1; then
    return
  fi
  if [ -n "${DOCKER_DESKTOP_EXE:-}" ] && [ -x "${DOCKER_DESKTOP_EXE}" ]; then
    log "Docker 未运行，尝试启动 Docker Desktop：$DOCKER_DESKTOP_EXE"
    "$DOCKER_DESKTOP_EXE" >/dev/null 2>&1 &
    for _ in $(seq 1 45); do
      if docker info >/dev/null 2>&1; then
        log "Docker 已就绪"
        return
      fi
      sleep 2
    done
  fi
  die "Docker 未运行。请先启动 Windows 上的 Docker Desktop，再重试；或设置 DOCKER_DESKTOP_EXE 指向 Docker Desktop.exe"
}

wait_url() {
  local url="$1"
  local seconds="$2"
  local name="$3"
  local deadline=$((SECONDS + seconds))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      log "$name 已就绪"
      return 0
    fi
    sleep 2
  done
  return 1
}

ensure_frontend_deps() {
  command -v node >/dev/null 2>&1 || die "未找到 node；需要 Node.js 22+"
  if [ ! -x "$ROOT/frontend/node_modules/.bin/vite" ]; then
    log "安装前端依赖（首次启动可能需要几分钟）"
    if [ -f "$ROOT/frontend/package-lock.json" ]; then
      npm --prefix "$ROOT/frontend" ci
    else
      npm --prefix "$ROOT/frontend" install
    fi
  fi
}

frontend_pid() {
  if [ -f "$FRONTEND_PID" ]; then
    cat "$FRONTEND_PID"
  fi
}

stop_frontend() {
  local pid
  pid="$(frontend_pid)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    log "停止 Vite（PID $pid）"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.2
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$FRONTEND_PID"
}

start_frontend() {
  ensure_frontend_deps
  local pid
  pid="$(frontend_pid)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    log "Vite 已在运行（PID $pid）"
    return
  fi
  if curl -fsS http://127.0.0.1:5173/login >/dev/null 2>&1; then
    log "检测到 5173 已有前端服务，复用现有进程"
    return
  fi

  mkdir -p "$RUN_DIR"
  log "启动 Vite（日志：$FRONTEND_LOG）"
  (
    cd "$ROOT/frontend"
    nohup ./node_modules/.bin/vite --host 0.0.0.0 > "$FRONTEND_LOG" 2>&1 &
    echo $! > "$FRONTEND_PID"
  )
  wait_url http://127.0.0.1:5173/login 90 "Vite" \
    || die "Vite 启动失败，请查看 $FRONTEND_LOG"
}

print_summary() {
  local wsl_ip=""
  wsl_ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
  cat <<EOF

============================================================
  三尺讲台 · 本地验收环境
============================================================
  前端：      http://localhost:5173/login
  备用：      ${wsl_ip:+http://$wsl_ip:5173/login}
  后端健康：  http://localhost:8090/actuator/health

  账号（密码统一 Demo1234!）：
    student.a01   学生
    teacher.a1    教师
    admin.a       管理员

  录制页（课堂 / 面试 / 训练）必须用 localhost 打开；
  LAN IP 不是安全上下文，浏览器会拒绝摄像头 / 屏幕共享。

  常用命令：
    ./scripts/dev.sh status
    ./scripts/dev.sh logs app
    ./scripts/dev.sh down
============================================================
EOF
}

cmd_up() {
  local do_build=0
  local do_frontend=1
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --build) do_build=1 ;;
      --no-frontend) do_frontend=0 ;;
      *) die "未知参数：$1（可用：--build / --no-frontend）" ;;
    esac
    shift
  done

  ensure_env
  ensure_docker

  local build_flag=()
  if [ "$do_build" = "1" ]; then
    build_flag=(--build)
    log "构建并启动 Docker 服务（源码改动后使用）"
  else
    log "启动 Docker 服务（镜像不存在时会自动构建；源码改动请用 up --build）"
  fi

  if ! compose up -d "${build_flag[@]}"; then
    die "Compose 启动 / 构建失败。若为 Maven Central TLS 握手问题，请先执行：
  docker build --network=host -t shijiangtai-app:latest ./server
然后重新运行：./scripts/dev.sh up"
  fi
  wait_url http://127.0.0.1:8090/actuator/health 180 "后端" \
    || die "后端未就绪，请执行：./scripts/dev.sh logs app"

  if [ "$do_frontend" = "1" ]; then
    start_frontend
  fi

  compose ps
  print_summary
}

cmd_build() {
  ensure_docker
  log "构建 app / ai / eval-worker 镜像"
  if ! compose build app ai eval-worker; then
    die "Compose 构建失败。若为 Maven Central TLS 握手问题，可改用：docker build --network=host -t shijiangtai-app:latest ./server"
  fi
}

cmd_status() {
  ensure_docker
  log "容器状态"
  compose ps || true
  printf '\n后端健康：'
  if curl -fsS http://127.0.0.1:8090/actuator/health 2>/dev/null; then
    printf '\n'
  else
    printf '未就绪\n'
  fi
  printf '前端：'
  if curl -fsS -o /dev/null http://127.0.0.1:5173/login 2>/dev/null; then
    printf 'http://localhost:5173/login\n'
  else
    printf '未就绪\n'
  fi
  local pid
  pid="$(frontend_pid)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    printf 'Vite PID：%s（日志 %s）\n' "$pid" "$FRONTEND_LOG"
  fi
}

cmd_logs() {
  ensure_docker
  if [ "$#" -eq 0 ]; then
    printf '前端日志：%s\n' "$FRONTEND_LOG"
    printf '按 Ctrl+C 退出；查看单个服务：./scripts/dev.sh logs app\n\n'
    compose logs -f --tail=60 app eval-worker
    return
  fi
  if [ "$1" = "frontend" ]; then
    mkdir -p "$RUN_DIR"
    touch "$FRONTEND_LOG"
    tail -n 100 -f "$FRONTEND_LOG"
    return
  fi
  compose logs -f --tail=100 "$@"
}

cmd_down() {
  stop_frontend
  ensure_docker
  log "停止并删除容器（数据卷保留）"
  compose down
}

cmd_stop() {
  stop_frontend
  ensure_docker
  log "停止容器（保留容器与数据卷）"
  compose stop
}

cmd_restart() {
  stop_frontend
  ensure_docker
  compose down
  cmd_up "$@"
}

cmd_reset_demo() {
  local assume_yes=0
  if [ "${1:-}" = "--yes" ]; then
    assume_yes=1
  fi
  if [ "$assume_yes" != "1" ]; then
    printf '将删除并重建 shijiangtai_demo 数据库，所有 demo 数据会丢失。\n'
    read -r -p '继续？输入 yes：' answer
    [ "$answer" = "yes" ] || die "已取消"
  fi

  ensure_env
  ensure_docker
  log "重建 demo 数据库"
  "$ROOT/server/docker/reset-test-db.sh" demo
  log "执行 V0—V901 + demo seed"
  compose --profile demo run --rm flyway-demo
  if [ -x "$ROOT/server/docker/seed-demo-assets.sh" ]; then
    log "上传 demo 媒体资源（无 ffmpeg 时自动跳过）"
    "$ROOT/server/docker/seed-demo-assets.sh" || true
  fi
  log "重建 app 容器并等待健康"
  compose up -d --force-recreate app
  wait_url http://127.0.0.1:8090/actuator/health 180 "后端" \
    || die "后端未就绪，请执行：./scripts/dev.sh logs app"
  print_summary
}

usage() {
  cat <<'EOF'
用法：./scripts/dev.sh <命令> [参数]

命令：
  up [--build] [--no-frontend]  启动 Docker 栈；--build 重建镜像后再启动
  build                         只构建 app / ai / eval-worker 镜像
  status                        查看容器、后端健康、前端地址
  logs [service|frontend]       跟踪日志；service 例如 app / ai / eval-worker
  stop                          停止容器（保留容器与数据卷）
  down                          停止并删除容器（保留数据卷）
  restart [--build]             重建容器并启动
  reset-demo [--yes]            重建 demo 数据库并重跑 demo seed
  frontend                      只启动 Vite（不启动 Docker）
  help                          显示本帮助
EOF
}

cmd_frontend() {
  start_frontend
  print_summary
}

cmd="${1:-up}"
shift || true
case "$cmd" in
  up) cmd_up "$@" ;;
  build) cmd_build ;;
  status) cmd_status ;;
  logs) cmd_logs "$@" ;;
  stop) cmd_stop ;;
  down) cmd_down ;;
  restart) cmd_restart "$@" ;;
  reset-demo) cmd_reset_demo "$@" ;;
  frontend) cmd_frontend ;;
  help|-h|--help) usage ;;
  *) usage; die "未知命令：$cmd" ;;
esac
