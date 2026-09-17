-- ============================================================
-- V1__init.sql
-- PDF Browser initial database schema
-- PostgreSQL
-- ============================================================


-- ============================================================
-- 1. tenant
-- 租户
-- ============================================================

CREATE TABLE tenant (
    id          BIGSERIAL PRIMARY KEY,
    slug        VARCHAR(100) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_tenant_slug UNIQUE (slug)
);


-- ============================================================
-- 2. app_user
-- 用户
-- ============================================================

CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    display_name    VARCHAR(255),
    email           VARCHAR(255),
    password_hash   VARCHAR(255) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_email UNIQUE (email)
);


-- ============================================================
-- 3. role
-- 角色
-- ============================================================

CREATE TABLE role (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(100) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_role_code UNIQUE (code)
);


-- ============================================================
-- 4. permission
-- 权限
-- ============================================================

CREATE TABLE permission (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(150) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,

    CONSTRAINT uk_permission_code UNIQUE (code)
);


-- ============================================================
-- 5. role_permission
-- 角色与权限的多对多关系
-- ============================================================

CREATE TABLE role_permission (
    role_id          BIGINT NOT NULL,
    permission_id    BIGINT NOT NULL,

    PRIMARY KEY (role_id, permission_id),

    CONSTRAINT fk_role_permission_role
        FOREIGN KEY (role_id)
        REFERENCES role (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_role_permission_permission
        FOREIGN KEY (permission_id)
        REFERENCES permission (id)
        ON DELETE CASCADE
);


CREATE INDEX idx_role_permission_permission_id
    ON role_permission (permission_id);


-- ============================================================
-- 6. membership
-- 用户在租户中的成员关系
-- ============================================================

CREATE TABLE membership (
    tenant_id       BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    role_id         BIGINT NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    joined_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_membership_tenant_user
        UNIQUE (tenant_id, user_id),

    CONSTRAINT fk_membership_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_membership_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_membership_role
        FOREIGN KEY (role_id)
        REFERENCES role (id)
);


CREATE INDEX idx_membership_user_id
    ON membership (user_id);

CREATE INDEX idx_membership_role_id
    ON membership (role_id);

CREATE INDEX idx_membership_tenant_status
    ON membership (tenant_id, status);


-- ============================================================
-- 7. task
-- 异步任务
-- ============================================================

CREATE TABLE task (
    id                  BIGSERIAL PRIMARY KEY,

    tenant_id           BIGINT NOT NULL,
    created_by_user_id  BIGINT,

    type                VARCHAR(100) NOT NULL,
    status              VARCHAR(50) NOT NULL,
    priority            INTEGER NOT NULL DEFAULT 0,

    payload             JSONB NOT NULL DEFAULT '{}'::jsonb,
    progress            INTEGER NOT NULL DEFAULT 0,

    attempts            INTEGER NOT NULL DEFAULT 0,
    error_message       TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,

    CONSTRAINT fk_task_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (id),

    CONSTRAINT fk_task_created_by_user
        FOREIGN KEY (created_by_user_id)
        REFERENCES app_user (id),

    CONSTRAINT ck_task_progress
        CHECK (progress >= 0 AND progress <= 100),

    CONSTRAINT ck_task_attempts
        CHECK (attempts >= 0)
);


CREATE INDEX idx_task_tenant_id
    ON task (tenant_id);

CREATE INDEX idx_task_created_by_user_id
    ON task (created_by_user_id);

CREATE INDEX idx_task_tenant_status
    ON task (tenant_id, status);

CREATE INDEX idx_task_status_priority_created
    ON task (status, priority DESC, created_at);

CREATE INDEX idx_task_created_at
    ON task (created_at);


-- ============================================================
-- 8. task_event
-- 任务状态变化、操作等事件记录
-- ============================================================

CREATE TABLE task_event (
    id              BIGSERIAL PRIMARY KEY,

    task_id         BIGINT NOT NULL,

    event_type      VARCHAR(100) NOT NULL,

    from_status     VARCHAR(50),
    to_status       VARCHAR(50),

    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,

    actor_user_id   BIGINT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_task_event_task
        FOREIGN KEY (task_id)
        REFERENCES task (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_task_event_actor_user
        FOREIGN KEY (actor_user_id)
        REFERENCES app_user (id)
);


CREATE INDEX idx_task_event_task_id
    ON task_event (task_id);

CREATE INDEX idx_task_event_task_created_at
    ON task_event (task_id, created_at);

CREATE INDEX idx_task_event_actor_user_id
    ON task_event (actor_user_id);

CREATE INDEX idx_task_event_event_type
    ON task_event (event_type);


-- ============================================================
-- 9. outbox_event
-- Transactional Outbox
-- ============================================================

CREATE TABLE outbox_event (
    id                  BIGSERIAL PRIMARY KEY,

    aggregate_id        VARCHAR(128) NOT NULL,
    event_type          VARCHAR(150) NOT NULL,

    payload             JSONB NOT NULL DEFAULT '{}'::jsonb,

    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',

    attempts            INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,

    published_at        TIMESTAMPTZ,
    last_error          TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_outbox_event_attempts
        CHECK (attempts >= 0)
);


CREATE INDEX idx_outbox_event_status
    ON outbox_event (status);

CREATE INDEX idx_outbox_event_pending
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX idx_outbox_event_aggregate_id
    ON outbox_event (aggregate_id);

CREATE INDEX idx_outbox_event_event_type
    ON outbox_event (event_type);