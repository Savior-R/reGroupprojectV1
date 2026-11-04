-- M2 course tables. Cross-schema FKs are added in V10.
CREATE TABLE m2_course.courses (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid NOT NULL,
    name        varchar(150) NOT NULL,
    created_by  uuid NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'active',
    end_at      timestamptz,
    archived_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_courses_school_name UNIQUE (school_id, name),
    CONSTRAINT ck_courses_status CHECK (status IN ('active', 'archived')),
    CONSTRAINT ck_courses_name CHECK (length(btrim(name)) > 0)
);

CREATE TABLE m2_course.course_majors (
    course_id  uuid NOT NULL,
    major_id   uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (course_id, major_id)
);

CREATE TABLE m2_course.course_teachers (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id   uuid NOT NULL,
    account_id  uuid NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'active',
    joined_at   timestamptz NOT NULL DEFAULT now(),
    left_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_course_teachers_status CHECK (status IN ('active', 'removed'))
);

CREATE TABLE m2_course.course_students (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id          uuid NOT NULL,
    student_account_id uuid NOT NULL,
    join_method        varchar(16) NOT NULL,
    status             varchar(16) NOT NULL DEFAULT 'active',
    joined_at          timestamptz NOT NULL DEFAULT now(),
    left_at            timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_course_students_method CHECK (join_method IN ('self', 'class', 'account', 'admin')),
    CONSTRAINT ck_course_students_status CHECK (status IN ('active', 'left', 'removed'))
);
