-- 多角色（RBAC0 的 User-Role 分离）。
--
-- accounts.account_type_id 保留为「主身份」：决定注册时建立哪种档案
-- （student_profiles / teacher_profiles / school_admins），并被 M2—M7 的外键引用。
-- account_roles 承载「授权角色集合」：一个账号可同时是教师与管理员，
-- 授权判定取各角色权限的并集（PermissionAuthorizationManager）。
--
-- 保留主身份而不是整体迁移到多角色表，原因：
--   1. 主身份决定档案类型，注册后不可变（requirements/M1 §4-1、A17）；
--   2. 20 张表通过 accounts 关联档案，改动主键语义会波及 M2—M7。

CREATE TABLE m1_auth.account_roles (
    account_id  uuid        NOT NULL,
    role_code   varchar(32) NOT NULL,
    granted_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_account_roles PRIMARY KEY (account_id, role_code),
    CONSTRAINT fk_account_roles_account FOREIGN KEY (account_id)
        REFERENCES m1_auth.accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_account_roles_role FOREIGN KEY (role_code)
        REFERENCES m1_auth.account_types (code) ON DELETE RESTRICT,
    CONSTRAINT fk_account_roles_granted_by FOREIGN KEY (granted_by)
        REFERENCES m1_auth.accounts (id) ON DELETE SET NULL
);

COMMENT ON TABLE m1_auth.account_roles IS
    '账号授权角色集合（多角色）；accounts.account_type_id 为主身份，注册后不变';

-- 回填：每个既有账号至少拥有其主身份角色，保证上线后授权不失效
INSERT INTO m1_auth.account_roles (account_id, role_code, granted_by, created_at)
SELECT a.id, t.code, NULL, a.created_at
FROM m1_auth.accounts a
JOIN m1_auth.account_types t ON t.id = a.account_type_id
ON CONFLICT DO NOTHING;

-- 角色授予 / 撤销的接口权限（02 §4.1.1 约定：description 存权限码原文）
INSERT INTO m1_auth.api_permissions (code, name, module, protocol, method, path_pattern, description)
VALUES ('admin:roles', 'M1 授予附加角色', 'M1', 'REST', 'PUT',
        '/api/v1/admin/teachers/{account_id}/roles', 'admin:roles')
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.account_type_permissions (account_type_id, permission_id)
SELECT at.id, p.id
FROM m1_auth.account_types at
JOIN m1_auth.api_permissions p ON p.description = 'admin:roles'
WHERE at.code = 'admin'
ON CONFLICT DO NOTHING;
