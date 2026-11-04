-- M1 account / organization tables. Cross-schema FKs are added in V10.
CREATE TABLE m1_auth.schools (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(100) NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_schools_name CHECK (length(btrim(name)) > 0)
);

CREATE TABLE m1_auth.account_types (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        varchar(32) NOT NULL UNIQUE,
    name        varchar(32) NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'active',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_types_status CHECK (status IN ('active', 'inactive'))
);

CREATE TABLE m1_auth.accounts (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username         varchar(64) NOT NULL UNIQUE,
    password_hash    varchar(100) NOT NULL,
    account_type_id  uuid NOT NULL,
    school_id        uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_accounts_username CHECK (length(btrim(username)) > 0)
);

CREATE TABLE m1_auth.school_admins (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid NOT NULL,
    account_id  uuid NOT NULL,
    is_owner    boolean NOT NULL DEFAULT false,
    status      varchar(16) NOT NULL DEFAULT 'active',
    joined_at   timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_school_admins_status CHECK (status IN ('active', 'removed'))
);

CREATE TABLE m1_auth.majors (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid NOT NULL,
    name        varchar(100) NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'active',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_majors_school_name UNIQUE (school_id, name),
    CONSTRAINT ck_majors_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_majors_name CHECK (length(btrim(name)) > 0)
);

CREATE TABLE m1_auth.classes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid NOT NULL,
    major_id    uuid NOT NULL,
    grade       varchar(20) NOT NULL,
    name        varchar(50) NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'active',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_classes_natural UNIQUE (major_id, grade, name),
    CONSTRAINT ck_classes_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_classes_name CHECK (length(btrim(grade)) > 0 AND length(btrim(name)) > 0)
);

CREATE TABLE m1_auth.student_profiles (
    account_id  uuid PRIMARY KEY,
    class_id    uuid NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE m1_auth.teacher_profiles (
    account_id  uuid PRIMARY KEY,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE m1_auth.teacher_majors (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     uuid NOT NULL,
    major_id       uuid NOT NULL,
    status         varchar(16) NOT NULL DEFAULT 'active',
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_to   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_teacher_majors_status CHECK (status IN ('active', 'inactive'))
);

CREATE TABLE m1_auth.api_permissions (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code         varchar(64) NOT NULL UNIQUE,
    name         varchar(100) NOT NULL,
    module       varchar(16) NOT NULL,
    protocol     varchar(16) NOT NULL,
    method       varchar(10) NOT NULL,
    path_pattern varchar(255) NOT NULL,
    description  text,
    status       varchar(16) NOT NULL DEFAULT 'active',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_api_permissions_route UNIQUE (protocol, method, path_pattern),
    CONSTRAINT ck_api_permissions_status CHECK (status IN ('active', 'inactive'))
);

CREATE TABLE m1_auth.account_type_permissions (
    account_type_id uuid NOT NULL,
    permission_id   uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_type_id, permission_id)
);
