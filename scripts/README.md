# 本地启动 / 验收脚本

`scripts/dev.sh` 把本项目本地验收需要的步骤收成一个命令：

- 检查 Docker Desktop 是否运行；
- 首次自动从 `server/.env.example` 生成 `server/.env`，补齐 JWT / 邮箱 / 内部令牌随机密钥；
- 用 `docker-compose.yml` + `scripts/compose.demo.yml` 启动
  PostgreSQL / Redis / MinIO / AI / app / eval-worker；
- 等待后端 `/actuator/health`；
- 启动 Vite；
- 打印访问地址和 demo 账号。

## 常用命令

```bash
# 首次启动（或源码改动后重建镜像）
./scripts/dev.sh up --build

# 日常启动（镜像已存在）
./scripts/dev.sh up

# 只看状态
./scripts/dev.sh status

# 跟踪日志
./scripts/dev.sh logs app
./scripts/dev.sh logs eval-worker
./scripts/dev.sh logs frontend

# 停止并删除容器（postgres / minio 数据卷保留）
./scripts/dev.sh down

# 只停止容器，保留容器与数据卷
./scripts/dev.sh stop

# 重建 demo 数据库（会清空 demo 数据）
./scripts/dev.sh reset-demo --yes

# 只启动前端（后端已在运行）
./scripts/dev.sh frontend
```

## 访问地址

- 前端：<http://localhost:5173/login>
- 后端健康：<http://localhost:8090/actuator/health>
- WSL IP 备用：`http://<WSL-IP>:5173/login`

账号密码统一 `Demo1234!`：

| 账号 | 角色 |
|---|---|
| `student.a01` | 学生 |
| `teacher.a1` | 教师 |
| `admin.a` | 管理员 |

## 录制页必须用 localhost

课堂 / 面试 / 训练录制依赖 `getUserMedia` / `getDisplayMedia`，浏览器只在安全上下文暴露这些 API：

- ✅ `http://localhost:5173`
- ✅ `https://...`
- ❌ `http://192.168.x.x:5173`

所以录制验收请在 Windows 宿主机浏览器打开 `http://localhost:5173`。普通页面（课程、任务、报告、管理端）可以用 LAN IP。

## 常见问题

### Docker 未运行

脚本会提示启动 Windows 上的 Docker Desktop。也可以在 WSL 中指定路径自动拉起：

```bash
export DOCKER_DESKTOP_EXE='/mnt/c/Program Files/Docker/Docker/Docker Desktop.exe'
./scripts/dev.sh up
```

### 后端构建卡在 Maven Central TLS 握手

先单独构建 app 镜像：

```bash
docker build --network=host -t shijiangtai-app:latest ./server
./scripts/dev.sh up
```

### 评测一直 pending

确认 `eval-worker` 在运行：

```bash
./scripts/dev.sh status
./scripts/dev.sh logs eval-worker
```

FastAPI `ai` 服务只提供 HTTP capability，不消费 Redis Stream；`eval-worker` 才是评测消费者。

### 端口被占用

- `5173`：脚本检测到已有服务会复用；需要重启时先 `./scripts/dev.sh down`。
- `8090`：本机 `8080` 已被其他项目占用，Compose 固定映射到 8090；如果 8090 也被占用，请先释放端口。
- `9000/9001`：MinIO；回放预签名 URL 默认指向 `http://localhost:9000`。

### 前端日志

```bash
tail -f .run/frontend.log
```
