-- 智能助教引擎 · MySQL 建库脚本（UTF-8）
CREATE DATABASE IF NOT EXISTS tutor_engine DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE tutor_engine;

CREATE TABLE IF NOT EXISTS conversation (
    id          VARCHAR(40)  NOT NULL COMMENT '会话id(s_xxx)',
    user_id     BIGINT       NOT NULL,
    title       VARCHAR(128) NULL,
    state       VARCHAR(20)  NULL COMMENT '状态机当前态',
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB COMMENT '会话表';

CREATE TABLE IF NOT EXISTS message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(40)  NOT NULL,
    msg_id          VARCHAR(40)  NOT NULL COMMENT '前端展示消息id(m_xxx)',
    role            VARCHAR(16)  NOT NULL COMMENT 'user/assistant',
    type            VARCHAR(20)  NOT NULL COMMENT '8+2种消息类型',
    content_json    MEDIUMTEXT   NOT NULL COMMENT 'content 与实时卡片完全一致',
    ts              BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv_ts (conversation_id, ts)
) ENGINE = InnoDB COMMENT '消息表（卡片/文本统一落库）';

CREATE TABLE IF NOT EXISTS exercise (
    exercise_id      VARCHAR(40)  NOT NULL COMMENT '幂等键(e_xxx)',
    conversation_id  VARCHAR(40)  NOT NULL,
    questions_json   MEDIUMTEXT   NOT NULL,
    knowledge_points VARCHAR(255) NULL,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (exercise_id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '练习表';

CREATE TABLE IF NOT EXISTS grade_result (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    exercise_id      VARCHAR(40)  NOT NULL COMMENT '唯一约束兜底幂等',
    conversation_id  VARCHAR(40)  NOT NULL,
    score            INT          NOT NULL,
    total_score      INT          NOT NULL,
    per_question_json MEDIUMTEXT  NOT NULL,
    knowledge_points VARCHAR(255) NULL,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exercise (exercise_id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '批改结果表';

CREATE TABLE IF NOT EXISTS learning_record (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    knowledge_point VARCHAR(64) NOT NULL,
    correct         TINYINT     NOT NULL COMMENT '1对 0错',
    time_ms         INT         NOT NULL DEFAULT 0 COMMENT '答题耗时ms,0=未知',
    ts              BIGINT      NOT NULL,
    created_at      DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user (user_id, ts)
) ENGINE = InnoDB COMMENT '学习记录（认知诊断数据源）';

CREATE TABLE IF NOT EXISTS diagnosis_report (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id  VARCHAR(40) NOT NULL,
    weak_points_json MEDIUMTEXT  NOT NULL,
    summary          VARCHAR(1024) NULL,
    created_at       DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '诊断报告表';

CREATE TABLE IF NOT EXISTS learning_plan (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(40)  NOT NULL,
    path_json       MEDIUMTEXT   NOT NULL,
    progress        DECIMAL(5, 4) NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '学习计划表';

CREATE TABLE IF NOT EXISTS course_order (
    order_id        VARCHAR(40)  NOT NULL COMMENT '幂等键(o_xxx)',
    user_id         BIGINT       NOT NULL,
    conversation_id VARCHAR(40)  NOT NULL,
    course_id       VARCHAR(40)  NOT NULL,
    course_name     VARCHAR(128) NOT NULL,
    price           INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'CREATED/PAID',
    paid_at         BIGINT       NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (order_id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '课程订单表（旁路交易域）';

CREATE TABLE IF NOT EXISTS ai_trace (
    id                VARCHAR(40)  NOT NULL COMMENT 'trace_id(t_xxx)',
    session_id        VARCHAR(40)  NOT NULL,
    user_id           BIGINT       NOT NULL,
    message           VARCHAR(512) NULL,
    intent            VARCHAR(24)  NULL,
    route_source      VARCHAR(24)  NULL,
    expected_intent   VARCHAR(24)  NULL COMMENT '标注:期望意图',
    expected_slots    VARCHAR(1024) NULL COMMENT '标注:期望槽位JSON',
    actual_slots      VARCHAR(1024) NULL COMMENT '实际路由槽位JSON',
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    cost              DECIMAL(10,6) NOT NULL DEFAULT 0,
    latency_ms        BIGINT       NOT NULL DEFAULT 0,
    status            VARCHAR(12)  NOT NULL,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session (session_id),
    KEY idx_created (created_at)
) ENGINE=InnoDB COMMENT 'AI调用链路';

CREATE TABLE IF NOT EXISTS ai_observation (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id          VARCHAR(40)  NOT NULL,
    parent_id         BIGINT       NULL,
    type              VARCHAR(12)  NOT NULL COMMENT 'SPAN/GENERATION',
    name              VARCHAR(64)  NOT NULL,
    tool              VARCHAR(128) NULL,
    model             VARCHAR(40)  NULL,
    input             TEXT         NULL,
    output            MEDIUMTEXT   NULL,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    cost              DECIMAL(10,6) NOT NULL DEFAULT 0,
    latency_ms        BIGINT       NOT NULL DEFAULT 0,
    status            VARCHAR(12)  NOT NULL,
    error             VARCHAR(512) NULL,
    ts                BIGINT       NOT NULL,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_trace (trace_id)
) ENGINE=InnoDB COMMENT 'AI观测节点';

CREATE TABLE IF NOT EXISTS ai_score (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    trace_id   VARCHAR(40) NOT NULL,
    session_id VARCHAR(40) NOT NULL,
    source     VARCHAR(16) NOT NULL DEFAULT 'user',
    rating     TINYINT     NOT NULL,
    comment    VARCHAR(512) NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_trace (trace_id)
) ENGINE=InnoDB COMMENT 'AI质量评分';

CREATE TABLE IF NOT EXISTS evaluation_report (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    start_at         DATETIME     NOT NULL COMMENT '评估时间窗起点',
    end_at           DATETIME     NOT NULL COMMENT '评估时间窗终点',
    trace_count      INT          NOT NULL DEFAULT 0,
    labeled_count    INT          NOT NULL DEFAULT 0,
    avg_score        DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '三路加权平均分（百分制）',
    metric_averages  VARCHAR(2048) NULL COMMENT '指标均值JSON',
    low_score_samples TEXT        NULL COMMENT '低分样本清单JSON（<60回流）',
    include_judge    TINYINT      NOT NULL DEFAULT 0,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_created (created_at)
) ENGINE=InnoDB COMMENT '批量评估报告';

CREATE TABLE IF NOT EXISTS user_account (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    password    VARCHAR(64)  NOT NULL COMMENT '登录密码',
    nickname    VARCHAR(64)  NULL COMMENT '用户昵称',
    role        VARCHAR(32)  NOT NULL DEFAULT 'ROLE_USER' COMMENT '角色: ROLE_USER / ROLE_ADMIN',
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '用户账户表';
