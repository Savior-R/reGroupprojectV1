-- M1 login methods (12 §14): accounts email binding, OAuth identities,
-- email login codes and login audit; plus `auth:self` permission seed.
-- Source: 12 §3.1 DDL, §11 foreign keys, §12 indexes; 02 §4.1.1 permission codes.
-- Forward-only: do not edit V1 / V10 / V11 / V12.

-- ---------------------------------------------------------------- accounts
ALTER TABLE m1_auth.accounts
  ADD COLUMN email varchar(254),
  ADD COLUMN email_verified_at timestamptz;

ALTER TABLE m1_auth.accounts
  ADD CONSTRAINT ck_accounts_email CHECK (email IS NULL OR email = lower(email));

-- -------------------------------------------------------- oauth_identities
CREATE TABLE m1_auth.oauth_identities (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id        uuid NOT NULL,
    provider          varchar(32) NOT NULL,
    provider_user_id  varchar(128) NOT NULL,
    union_id          varchar(128),
    email             varchar(254),
    nickname          varchar(100),
    avatar_url        varchar(500),
    created_at        timestamptz NOT NULL DEFAULT now(),
    last_login_at     timestamptz,
    CONSTRAINT uq_oauth_identity UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_oauth_account_provider UNIQUE (account_id, provider),
    CONSTRAINT ck_oauth_provider CHECK (provider IN ('wechat'))
);

-- ------------------------------------------------------- email_login_codes
CREATE TABLE m1_auth.email_login_codes (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         varchar(254) NOT NULL,
    code_hash     varchar(64) NOT NULL,
    purpose       varchar(16) NOT NULL,
    expires_at    timestamptz NOT NULL,
    attempts      integer NOT NULL DEFAULT 0,
    consumed_at   timestamptz,
    request_ip    inet,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_code_purpose CHECK (purpose IN ('login', 'bind')),
    CONSTRAINT ck_email_code_attempts CHECK (attempts >= 0)
);

-- -------------------------------------------------------- auth_login_events
CREATE TABLE m1_auth.auth_login_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid,
    method      varchar(16) NOT NULL,
    provider    varchar(32),
    success     boolean NOT NULL,
    ip          inet,
    user_agent  varchar(500),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_login_method CHECK (method IN ('password', 'email', 'wechat'))
);

-- --------------------------------------------------------------- foreign keys
ALTER TABLE m1_auth.oauth_identities
  ADD CONSTRAINT fk_oauth_identities_account FOREIGN KEY (account_id)
  REFERENCES m1_auth.accounts(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.auth_login_events
  ADD CONSTRAINT fk_auth_login_events_account FOREIGN KEY (account_id)
  REFERENCES m1_auth.accounts(id) ON DELETE SET NULL;

-- ------------------------------------------------------------------- indexes
CREATE UNIQUE INDEX uq_accounts_email ON m1_auth.accounts (lower(email))
  WHERE email IS NOT NULL;
CREATE INDEX idx_oauth_identities_account ON m1_auth.oauth_identities (account_id);
CREATE INDEX idx_oauth_identities_union ON m1_auth.oauth_identities (provider, union_id);
CREATE INDEX idx_email_login_codes_email ON m1_auth.email_login_codes (email, created_at);
CREATE INDEX idx_email_login_codes_expires ON m1_auth.email_login_codes (expires_at);
CREATE INDEX idx_auth_login_events_account ON m1_auth.auth_login_events (account_id, created_at);
CREATE INDEX idx_auth_login_events_method ON m1_auth.auth_login_events (method, created_at);

-- ------------------------------------------------------- permission seed
-- 02 §4.1.1: bind / unbind routes require login and use `auth:self`;
-- public login / QR / callback routes stay out of api_permissions on purpose.
WITH routes(module, protocol, method, path_pattern, perm_code) AS (
  VALUES
    ('M1', 'REST', 'POST',   '/api/v1/auth/email/bind/code', 'auth:self'),
    ('M1', 'REST', 'POST',   '/api/v1/auth/email/bind', 'auth:self'),
    ('M1', 'REST', 'DELETE', '/api/v1/auth/email', 'auth:self'),
    ('M1', 'REST', 'POST',   '/api/v1/auth/oauth/wechat/bind', 'auth:self'),
    ('M1', 'REST', 'DELETE', '/api/v1/auth/oauth/wechat', 'auth:self')
),
ranked AS (
  SELECT *,
         row_number() OVER (PARTITION BY perm_code ORDER BY path_pattern, method) AS rn
  FROM routes
)
INSERT INTO m1_auth.api_permissions (code, name, module, protocol, method, path_pattern, description)
SELECT
  CASE WHEN rn = 1 THEN perm_code ELSE perm_code || '#' || rn::text END,
  module || ' ' || perm_code,
  module,
  protocol,
  method,
  path_pattern,
  perm_code
FROM ranked
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.account_type_permissions (account_type_id, permission_id)
SELECT at.id, p.id
FROM m1_auth.account_types at
JOIN m1_auth.api_permissions p ON p.description = 'auth:self'
WHERE at.code IN ('student', 'teacher', 'admin')
ON CONFLICT DO NOTHING;
