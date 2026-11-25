# M1 E2E 测试（Playwright）

覆盖 `docs/design/10-测试与验收.md` §6.1 的 E2E 用例：

| 用例 | 文件 | 内容 |
|---|---|---|
| M1-T08 | `tests/m1-t08-account-readonly.spec.ts` | 账号信息只读展示；无修改资料/改密码接口（PUT/PATCH `/auth/me` 返回 404/405） |
| M1-T09 | `tests/m1-t09-admin-setup.spec.ts` | 未建校管理员被守卫强制到 `/admin/setup`；建校后可进入管理端 |
| M1-T10 | `tests/m1-t10-rbac-403.spec.ts` | 学生/教师访问管理路由前端跳 `/403`；后端 RBAC 返回 403 |

## 运行前置

```bash
# 1. 独立 E2E 数据库（避免与集成测试共用 shijiangtai 库）
docker exec shijiangtai-postgres-1 psql -U shijiangtai -d postgres \
  -c "CREATE DATABASE shijiangtai_e2e OWNER shijiangtai;"

# 2. 后端（隔离目录或本地任意方式），test profile 强制 Mock 邮件/微信
#    SERVER_PORT=8091
#    SPRING_PROFILES_ACTIVE=test
#    SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/shijiangtai_e2e?stringtype=unspecified
cd /tmp/e2e-server && gradle bootRun   # 或仓库内等待测试空闲后 bootRun

# 3. 前端 dev server（代理指向 8091）
cd frontend && VITE_API_TARGET=http://localhost:8091 npm run dev

# 4. Playwright 浏览器（首次）
npx playwright install chromium
```

## 执行

```bash
cd frontend
npm run e2e                     # 全部 3 个 spec
npm run e2e -- -g "M1-T09"      # 单用例
```

种子账号由 `helpers/global-setup.ts` 经 REST API 幂等创建，写入 `e2e/.state.json`：

- `E2E未建校管理员`（admin，无 school —— T09）
- `E2E建校管理员`（admin，已建校/专业/班级 —— T09/T10 依赖数据）
- `E2E教师甲`（teacher，绑定专业）
- `E2E学生甲`（student，绑定班级）

密码统一 `Passw0rd!23`，仅用于本地 E2E 库。
