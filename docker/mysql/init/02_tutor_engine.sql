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

-- 学习记录种子数据（user_id=1，认知诊断演示数据源）：
-- 模拟「非计算机转Java后端」学生学情：集合框架/多线程/MySQL索引为薄弱点，Java基础/SpringBoot掌握较好
USE tutor_engine;

INSERT INTO learning_record (user_id, knowledge_point, correct, time_ms, ts, created_at) VALUES
-- 集合框架：10 题对 4（正确率 40%，高频错误，部分题耗时超基准 60s）
(1, '集合框架', 0, 72000, 1756300000001, NOW()),
(1, '集合框架', 1, 45000, 1756300100002, NOW()),
(1, '集合框架', 0, 88000, 1756300200003, NOW()),
(1, '集合框架', 0, 65000, 1756300300004, NOW()),
(1, '集合框架', 1, 38000, 1756300400005, NOW()),
(1, '集合框架', 0, 95000, 1756300500006, NOW()),
(1, '集合框架', 1, 52000, 1756300600007, NOW()),
(1, '集合框架', 0, 78000, 1756300700008, NOW()),
(1, '集合框架', 1, 41000, 1756300800009, NOW()),
(1, '集合框架', 0, 70000, 1756300900010, NOW()),
-- 多线程与并发：8 题对 4（正确率 50%，耗时普遍偏长）
(1, '多线程与并发', 0, 92000, 1756301000011, NOW()),
(1, '多线程与并发', 1, 66000, 1756301100012, NOW()),
(1, '多线程与并发', 0, 105000, 1756301200013, NOW()),
(1, '多线程与并发', 1, 58000, 1756301300014, NOW()),
(1, '多线程与并发', 0, 98000, 1756301400015, NOW()),
(1, '多线程与并发', 1, 72000, 1756301500016, NOW()),
(1, '多线程与并发', 1, 69000, 1756301600017, NOW()),
(1, '多线程与并发', 0, 112000, 1756301700018, NOW()),
-- MySQL索引与优化：8 题对 4（正确率 50%，执行计划分析类题目耗时较长）
(1, 'MySQL索引与优化', 0, 85000, 1756301800019, NOW()),
(1, 'MySQL索引与优化', 1, 55000, 1756301900020, NOW()),
(1, 'MySQL索引与优化', 0, 96000, 1756302000021, NOW()),
(1, 'MySQL索引与优化', 0, 78000, 1756302100022, NOW()),
(1, 'MySQL索引与优化', 1, 48000, 1756302200023, NOW()),
(1, 'MySQL索引与优化', 1, 62000, 1756302300024, NOW()),
(1, 'MySQL索引与优化', 0, 88000, 1756302400025, NOW()),
(1, 'MySQL索引与优化', 1, 51000, 1756302500026, NOW()),
-- JVM原理：6 题对 4（正确率 67%，GC/类加载部分掌握尚可，调优参数易错）
(1, 'JVM原理', 1, 42000, 1756302600027, NOW()),
(1, 'JVM原理', 0, 76000, 1756302700028, NOW()),
(1, 'JVM原理', 1, 38000, 1756302800029, NOW()),
(1, 'JVM原理', 1, 45000, 1756302900030, NOW()),
(1, 'JVM原理', 0, 82000, 1756303000031, NOW()),
(1, 'JVM原理', 1, 40000, 1756303100032, NOW()),
-- Spring核心：6 题对 4（正确率 67%，IoC/AOP概念清楚，循环依赖/事务传播易错）
(1, 'Spring核心', 1, 35000, 1756303200033, NOW()),
(1, 'Spring核心', 1, 42000, 1756303300034, NOW()),
(1, 'Spring核心', 0, 78000, 1756303400035, NOW()),
(1, 'Spring核心', 1, 39000, 1756303500036, NOW()),
(1, 'Spring核心', 0, 71000, 1756303600037, NOW()),
(1, 'Spring核心', 1, 44000, 1756303700038, NOW()),
-- Java基础语法：6 题对 5（正确率 83%，掌握较好，泛型边界偶有失误）
(1, 'Java基础语法', 1, 25000, 1756303800039, NOW()),
(1, 'Java基础语法', 1, 28000, 1756303900040, NOW()),
(1, 'Java基础语法', 1, 22000, 1756304000041, NOW()),
(1, 'Java基础语法', 0, 56000, 1756304100042, NOW()),
(1, 'Java基础语法', 1, 30000, 1756304200043, NOW()),
(1, 'Java基础语法', 1, 26000, 1756304300044, NOW()),
-- SpringBoot实战：5 题对 4（正确率 80%，自动配置/起步依赖熟练，自定义starter偶有失误）
(1, 'SpringBoot实战', 1, 33000, 1756304400045, NOW()),
(1, 'SpringBoot实战', 1, 36000, 1756304500046, NOW()),
(1, 'SpringBoot实战', 0, 68000, 1756304600047, NOW()),
(1, 'SpringBoot实战', 1, 31000, 1756304700048, NOW()),
(1, 'SpringBoot实战', 1, 29000, 1756304800049, NOW()),
-- Redis缓存：4 题对 3（正确率 75%，数据结构/持久化掌握尚可，缓存穿透/击穿方案偶混淆）
(1, 'Redis缓存', 1, 38000, 1756304900050, NOW()),
(1, 'Redis缓存', 1, 42000, 1756305000051, NOW()),
(1, 'Redis缓存', 0, 75000, 1756305100052, NOW()),
(1, 'Redis缓存', 1, 35000, 1756305200053, NOW());
