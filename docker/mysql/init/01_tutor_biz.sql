CREATE DATABASE IF NOT EXISTS tutor_biz DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE tutor_biz;
CREATE TABLE IF NOT EXISTS order_info (
    order_id    VARCHAR(40)  NOT NULL,
    course_id   VARCHAR(40)  NOT NULL,
    course_name VARCHAR(128) NOT NULL,
    price       INT          NOT NULL,
    user_id     BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL COMMENT 'PENDING_PAY/PAID',
    pay_no      VARCHAR(40)  NULL,
    created_at  DATETIME     NOT NULL,
    paid_at     DATETIME     NULL,
    PRIMARY KEY (order_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB COMMENT '订单表（业务真相）';
CREATE TABLE IF NOT EXISTS ticket_info (
    ticket_id   VARCHAR(40)  NOT NULL,
    order_id    VARCHAR(40)  NULL,
    user_id     BIGINT       NOT NULL,
    issue       VARCHAR(512) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (ticket_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB COMMENT '工单表';

CREATE TABLE IF NOT EXISTS course_info (
    course_id   VARCHAR(16)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    track       VARCHAR(32)  NULL COMMENT '内容板块',
    level       VARCHAR(16)  NULL COMMENT '入门/进阶/高级',
    price       INT          NOT NULL DEFAULT 0,
    hours       INT          NOT NULL DEFAULT 0,
    teacher     VARCHAR(64)  NULL,
    roles       VARCHAR(256) NULL COMMENT '目标岗位 JSON 数组',
    kps         VARCHAR(512) NULL COMMENT '知识点 JSON 数组',
    intro       TEXT         NULL COMMENT '课程介绍（语义检索源）',
    outline     TEXT         NULL COMMENT '课程大纲',
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (course_id),
    KEY idx_track (track)
) ENGINE=InnoDB COMMENT '课程主数据（语料摄入）';

CREATE TABLE IF NOT EXISTS question_bank (
    id          VARCHAR(32)  NOT NULL,
    course_id   VARCHAR(16)  NOT NULL,
    kp          VARCHAR(64)  NOT NULL,
    type        VARCHAR(16)  NOT NULL COMMENT 'choice/short',
    difficulty  VARCHAR(16)  NULL,
    score       INT          NOT NULL DEFAULT 5,
    stem        VARCHAR(1024) NOT NULL,
    options     VARCHAR(1024) NULL COMMENT '选项 JSON 数组',
    answer      VARCHAR(512)  NULL COMMENT '答案（权限隔离：抽题不下发）',
    analysis    TEXT          NULL COMMENT '解析/参考答案（权限隔离）',
    keywords    VARCHAR(512)  NULL COMMENT '评分关键词 JSON 数组',
    PRIMARY KEY (id),
    KEY idx_course (course_id),
    KEY idx_kp (kp)
) ENGINE=InnoDB COMMENT '课程私有题库（购课权益）';
