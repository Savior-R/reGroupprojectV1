# M1 性能 / 负载测试（Locust）

覆盖认证关键路径的负载测试，目标端点全部来自 M1（FR-M1-01/02/07）：

| 场景 | 权重 | 说明 |
|---|---|---|
| 密码登录（正确口令） | 2 | 全链路：BCrypt 校验 + JWT 签发 + 审计写入（重 I/O） |
| 密码登录（错误口令） | 1 | 失败路径：统一 UNAUTHORIZED + 失败审计，不泄露账号存在性 |
| `GET /auth/me`（Bearer） | 3 | JWT 验签 + 权限表驱动授权（最高频的受保护请求形态） |
| `GET /api/v1/org/schools` 公开选项 | 1 | 注册页公开查询（白名单路径） |

说明：邮箱验证码发送与注册接口不纳入压测——前者有 60 秒重发 / 小时限流（429 是预期行为），后者写入业务数据会污染库；两者如需压测应在独立环境专项进行。

## 运行前置

同 `frontend/e2e/README.md` 的独立后端（8091 + `shijiangtai_e2e` 库），并先跑一次
`npx playwright test`（或手动调种子接口）以生成压测账号。

## 执行

```bash
# 无 UI 模式：50 并发用户，每秒孵化 5 个，压 60 秒（必须用 locust CLI，勿用 python locustfile.py 直跑）
cd perf
locust -f locustfile.py --headless -u 50 -r 5 -t 60s \
  --host http://localhost:8091 --html report.html --csv report

# 指定压测账号（默认 e2e 学生/教师账号，见 .state.json）
E2E_STATE=../frontend/e2e/.state.json locust -f locustfile.py ...
```

## 通过基线（建议）

- 登录成功率 ≥ 99%；`/auth/me` 成功率 ≥ 99.9%
- p95 延迟：登录 ≤ 300ms；`/auth/me` ≤ 100ms（本机单实例基准，供回归对比）
- 无 5xx；限流路径（429）不算失败
