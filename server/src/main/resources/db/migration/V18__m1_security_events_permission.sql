-- V18: M1「我的登录记录」只读接口的权限种子（03 §3.9 /settings/security）。
--
-- 背景：V13 已为 bind / unbind 路由占用 5 条 `auth:self`（编号 auth:self ~ auth:self#5，
-- 按 path_pattern / method 排序）。新增路由 GET /api/v1/auth/security/events 属同一
-- 权限域，故沿用 description = 'auth:self' 并取下一个空闲 code。
-- 该接口只读本人审计记录，字段不含 token / 验证码（03 §3.10）。

INSERT INTO m1_auth.api_permissions (code, name, module, protocol, method, path_pattern, description)
VALUES ('auth:self#6', 'M1 auth:self', 'M1', 'REST', 'GET',
        '/api/v1/auth/security/events', 'auth:self')
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.account_type_permissions (account_type_id, permission_id)
SELECT at.id, p.id
FROM m1_auth.account_types at
JOIN m1_auth.api_permissions p ON p.description = 'auth:self'
WHERE at.code IN ('student', 'teacher', 'admin')
ON CONFLICT DO NOTHING;
