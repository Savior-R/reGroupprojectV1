-- V15: auth_login_events 增加事件类型，支撑 FR-M1-11 绑定 / 解绑审计。
-- event_type: login / bind / unbind；旧数据默认 login。
-- method 仍由 ck_login_method 约束为 password / email / wechat；
-- bind / unbind 只允许 email / wechat（密码不可绑定 / 解绑）。

ALTER TABLE m1_auth.auth_login_events
    ADD COLUMN event_type varchar(16) NOT NULL DEFAULT 'login';

ALTER TABLE m1_auth.auth_login_events
    ADD CONSTRAINT ck_event_type
        CHECK (event_type IN ('login', 'bind', 'unbind'));

ALTER TABLE m1_auth.auth_login_events
    ADD CONSTRAINT ck_event_method
        CHECK (event_type = 'login' OR method IN ('email', 'wechat'));

CREATE INDEX idx_auth_login_events_type
    ON m1_auth.auth_login_events (event_type, created_at);
