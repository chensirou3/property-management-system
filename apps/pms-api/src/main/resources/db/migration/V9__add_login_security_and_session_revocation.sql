ALTER TABLE sys_user
    ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0 AFTER password_change_required,
    ADD COLUMN password_changed_at DATETIME(3) NULL AFTER last_login_at;

CREATE TABLE auth_login_guard (
    guard_key VARCHAR(80) PRIMARY KEY,
    guard_type VARCHAR(20) NOT NULL,
    failure_count INT NOT NULL DEFAULT 0,
    window_started_at DATETIME(3) NOT NULL,
    locked_until DATETIME(3),
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT chk_auth_guard_failure_count CHECK (failure_count >= 0)
);

CREATE INDEX idx_auth_guard_lock ON auth_login_guard (locked_until, updated_at);
CREATE INDEX idx_audit_actor_action_time ON audit_event (actor_user_id, action_code, occurred_at);
