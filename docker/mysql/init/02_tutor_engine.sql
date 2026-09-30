-- 鏅鸿兘鍔╂暀寮曟搸 路 MySQL 寤哄簱鑴氭湰锛圲TF-8锛?
CREATE DATABASE IF NOT EXISTS tutor_engine DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE tutor_engine;

CREATE TABLE IF NOT EXISTS conversation (
    id          VARCHAR(40)  NOT NULL COMMENT '浼氳瘽id(s_xxx)',
    user_id     BIGINT       NOT NULL,
    title       VARCHAR(128) NULL,
    state       VARCHAR(20)  NULL COMMENT '鐘舵€佹満褰撳墠鎬?,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB COMMENT '浼氳瘽琛?;

CREATE TABLE IF NOT EXISTS message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(40)  NOT NULL,
    msg_id          VARCHAR(40)  NOT NULL COMMENT '鍓嶇灞曠ず娑堟伅id(m_xxx)',
    role            VARCHAR(16)  NOT NULL COMMENT 'user/assistant',
    type            VARCHAR(20)  NOT NULL COMMENT '8+2绉嶆秷鎭被鍨?,
    content_json    MEDIUMTEXT   NOT NULL COMMENT 'content 涓庡疄鏃跺崱鐗囧畬鍏ㄤ竴鑷?,
    ts              BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv_ts (conversation_id, ts)
) ENGINE = InnoDB COMMENT '娑堟伅琛紙鍗＄墖/鏂囨湰缁熶竴钀藉簱锛?;

CREATE TABLE IF NOT EXISTS exercise (
    exercise_id      VARCHAR(40)  NOT NULL COMMENT '骞傜瓑閿?e_xxx)',
    conversation_id  VARCHAR(40)  NOT NULL,
    questions_json   MEDIUMTEXT   NOT NULL,
    knowledge_points VARCHAR(255) NULL,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (exercise_id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '缁冧範琛?;

CREATE TABLE IF NOT EXISTS grade_result (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    exercise_id      VARCHAR(40)  NOT NULL COMMENT '鍞竴绾︽潫鍏滃簳骞傜瓑',
    conversation_id  VARCHAR(40)  NOT NULL,
    score            INT          NOT NULL,
    total_score      INT          NOT NULL,
    per_question_json MEDIUMTEXT  NOT NULL,
    knowledge_points VARCHAR(255) NULL,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exercise (exercise_id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '鎵规敼缁撴灉琛?;

CREATE TABLE IF NOT EXISTS learning_record (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    knowledge_point VARCHAR(64) NOT NULL,
    correct         TINYINT     NOT NULL COMMENT '1瀵?0閿?,
    time_ms         INT         NOT NULL DEFAULT 0 COMMENT '绛旈鑰楁椂ms,0=鏈煡',
    ts              BIGINT      NOT NULL,
    created_at      DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user (user_id, ts)
) ENGINE = InnoDB COMMENT '瀛︿範璁板綍锛堣鐭ヨ瘖鏂暟鎹簮锛?;

CREATE TABLE IF NOT EXISTS diagnosis_report (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id  VARCHAR(40) NOT NULL,
    weak_points_json MEDIUMTEXT  NOT NULL,
    summary          VARCHAR(1024) NULL,
    created_at       DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '璇婃柇鎶ュ憡琛?;

CREATE TABLE IF NOT EXISTS learning_plan (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(40)  NOT NULL,
    path_json       MEDIUMTEXT   NOT NULL,
    progress        DECIMAL(5, 4) NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB COMMENT '瀛︿範璁″垝琛?;

CREATE TABLE IF NOT EXISTS course_order (
    order_id        VARCHAR(40)  NOT NULL COMMENT '骞傜瓑閿?o_xxx)',
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
) ENGINE = InnoDB COMMENT '璇剧▼璁㈠崟琛紙鏃佽矾浜ゆ槗鍩燂級';

CREATE TABLE IF NOT EXISTS ai_trace (
    id                VARCHAR(40)  NOT NULL COMMENT 'trace_id(t_xxx)',
    session_id        VARCHAR(40)  NOT NULL,
    user_id           BIGINT       NOT NULL,
    message           VARCHAR(512) NULL,
    intent            VARCHAR(24)  NULL,
    route_source      VARCHAR(24)  NULL,
    expected_intent   VARCHAR(24)  NULL COMMENT '鏍囨敞:鏈熸湜鎰忓浘',
    expected_slots    VARCHAR(1024) NULL COMMENT '鏍囨敞:鏈熸湜妲戒綅JSON',
    actual_slots      VARCHAR(1024) NULL COMMENT '瀹為檯璺敱妲戒綅JSON',
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    cost              DECIMAL(10,6) NOT NULL DEFAULT 0,
    latency_ms        BIGINT       NOT NULL DEFAULT 0,
    status            VARCHAR(12)  NOT NULL,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session (session_id),
    KEY idx_created (created_at)
) ENGINE=InnoDB COMMENT 'AI璋冪敤閾捐矾';

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
) ENGINE=InnoDB COMMENT 'AI瑙傛祴鑺傜偣';

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
) ENGINE=InnoDB COMMENT 'AI璐ㄩ噺璇勫垎';

CREATE TABLE IF NOT EXISTS evaluation_report (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    start_at         DATETIME     NOT NULL COMMENT '璇勪及鏃堕棿绐楄捣鐐?,
    end_at           DATETIME     NOT NULL COMMENT '璇勪及鏃堕棿绐楃粓鐐?,
    trace_count      INT          NOT NULL DEFAULT 0,
    labeled_count    INT          NOT NULL DEFAULT 0,
    avg_score        DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '涓夎矾鍔犳潈骞冲潎鍒嗭紙鐧惧垎鍒讹級',
    metric_averages  VARCHAR(2048) NULL COMMENT '鎸囨爣鍧囧€糐SON',
    low_score_samples TEXT        NULL COMMENT '浣庡垎鏍锋湰娓呭崟JSON锛?60鍥炴祦锛?,
    include_judge    TINYINT      NOT NULL DEFAULT 0,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_created (created_at)
) ENGINE=InnoDB COMMENT '鎵归噺璇勪及鎶ュ憡';

CREATE TABLE IF NOT EXISTS user_account (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL COMMENT '鐧诲綍鐢ㄦ埛鍚?,
    password    VARCHAR(64)  NOT NULL COMMENT '鐧诲綍瀵嗙爜',
    nickname    VARCHAR(64)  NULL COMMENT '鐢ㄦ埛鏄电О',
    role        VARCHAR(32)  NOT NULL DEFAULT 'ROLE_USER' COMMENT '瑙掕壊: ROLE_USER / ROLE_ADMIN',
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '鐢ㄦ埛璐︽埛琛?;
-- 瀛︿範璁板綍绉嶅瓙鏁版嵁锛坲ser_id=1锛岃鐭ヨ瘖鏂紨绀烘暟鎹簮锛夛細
-- 妯℃嫙銆岄潪璁＄畻鏈鸿浆Java鍚庣銆嶅鐢熷鎯咃細闆嗗悎妗嗘灦/澶氱嚎绋?MySQL绱㈠紩涓鸿杽寮辩偣锛孞ava鍩虹/SpringBoot鎺屾彙杈冨ソ
USE tutor_engine;

INSERT INTO learning_record (user_id, knowledge_point, correct, time_ms, ts, created_at) VALUES
-- 闆嗗悎妗嗘灦锛?0 棰樺 4锛堟纭巼 40%锛岄珮棰戦敊璇紝閮ㄥ垎棰樿€楁椂瓒呭熀鍑?60s锛?(1, '闆嗗悎妗嗘灦', 0, 72000, 1756300000001, NOW()),
(1, '闆嗗悎妗嗘灦', 1, 45000, 1756300100002, NOW()),
(1, '闆嗗悎妗嗘灦', 0, 88000, 1756300200003, NOW()),
(1, '闆嗗悎妗嗘灦', 0, 65000, 1756300300004, NOW()),
(1, '闆嗗悎妗嗘灦', 1, 38000, 1756300400005, NOW()),
(1, '闆嗗悎妗嗘灦', 0, 95000, 1756300500006, NOW()),
(1, '闆嗗悎妗嗘灦', 1, 52000, 1756300600007, NOW()),
(1, '闆嗗悎妗嗘灦', 0, 78000, 1756300700008, NOW()),
(1, '闆嗗悎妗嗘灦', 1, 41000, 1756300800009, NOW()),
(1, '闆嗗悎妗嗘灦', 0, 70000, 1756300900010, NOW()),
-- 澶氱嚎绋嬩笌骞跺彂锛? 棰樺 4锛堟纭巼 50%锛岃€楁椂鏅亶鍋忛暱锛?(1, '澶氱嚎绋嬩笌骞跺彂', 0, 92000, 1756301000011, NOW()),
(1, '澶氱嚎绋嬩笌骞跺彂', 1, 66000, 1756301100012, NOW()),
(1, '澶氱嚎绋嬩笌骞跺彂', 0, 105000, 1756301200013, NOW()),
(1, '澶氱嚎绋嬩笌骞跺彂', 1, 58000, 1756301300014, NOW()),
(1, '澶氱嚎绋嬩笌骞跺彂', 0, 98000, 1756301400015, NOW()),
(1, '澶氱嚎绋嬩笌骞跺彂', 1, 72000, 1756301500016, NOW()),
(1, '澶氱嚎绋嬩笌骞跺彂', 1, 69000, 1756301600017, NOW()),
(1, '澶氱嚎绋嬩笌骞跺彂', 0, 112000, 1756301700018, NOW()),
-- MySQL绱㈠紩涓庝紭鍖栵細8 棰樺 4锛堟纭巼 50%锛屾墽琛岃鍒掑垎鏋愮被棰樼洰鑰楁椂杈冮暱锛?(1, 'MySQL绱㈠紩涓庝紭鍖?, 0, 85000, 1756301800019, NOW()),
(1, 'MySQL绱㈠紩涓庝紭鍖?, 1, 55000, 1756301900020, NOW()),
(1, 'MySQL绱㈠紩涓庝紭鍖?, 0, 96000, 1756302000021, NOW()),
(1, 'MySQL绱㈠紩涓庝紭鍖?, 0, 78000, 1756302100022, NOW()),
(1, 'MySQL绱㈠紩涓庝紭鍖?, 1, 48000, 1756302200023, NOW()),
(1, 'MySQL绱㈠紩涓庝紭鍖?, 1, 62000, 1756302300024, NOW()),
(1, 'MySQL绱㈠紩涓庝紭鍖?, 0, 88000, 1756302400025, NOW()),
(1, 'MySQL绱㈠紩涓庝紭鍖?, 1, 51000, 1756302500026, NOW()),
-- JVM鍘熺悊锛? 棰樺 4锛堟纭巼 67%锛孏C/绫诲姞杞介儴鍒嗘帉鎻″皻鍙紝璋冧紭鍙傛暟鏄撻敊锛?(1, 'JVM鍘熺悊', 1, 42000, 1756302600027, NOW()),
(1, 'JVM鍘熺悊', 0, 76000, 1756302700028, NOW()),
(1, 'JVM鍘熺悊', 1, 38000, 1756302800029, NOW()),
(1, 'JVM鍘熺悊', 1, 45000, 1756302900030, NOW()),
(1, 'JVM鍘熺悊', 0, 82000, 1756303000031, NOW()),
(1, 'JVM鍘熺悊', 1, 40000, 1756303100032, NOW()),
-- Spring鏍稿績锛? 棰樺 4锛堟纭巼 67%锛孖oC/AOP姒傚康娓呮锛屽惊鐜緷璧?浜嬪姟浼犳挱鏄撻敊锛?(1, 'Spring鏍稿績', 1, 35000, 1756303200033, NOW()),
(1, 'Spring鏍稿績', 1, 42000, 1756303300034, NOW()),
(1, 'Spring鏍稿績', 0, 78000, 1756303400035, NOW()),
(1, 'Spring鏍稿績', 1, 39000, 1756303500036, NOW()),
(1, 'Spring鏍稿績', 0, 71000, 1756303600037, NOW()),
(1, 'Spring鏍稿績', 1, 44000, 1756303700038, NOW()),
-- Java鍩虹璇硶锛? 棰樺 5锛堟纭巼 83%锛屾帉鎻¤緝濂斤紝娉涘瀷杈圭晫鍋舵湁澶辫锛?(1, 'Java鍩虹璇硶', 1, 25000, 1756303800039, NOW()),
(1, 'Java鍩虹璇硶', 1, 28000, 1756303900040, NOW()),
(1, 'Java鍩虹璇硶', 1, 22000, 1756304000041, NOW()),
(1, 'Java鍩虹璇硶', 0, 56000, 1756304100042, NOW()),
(1, 'Java鍩虹璇硶', 1, 30000, 1756304200043, NOW()),
(1, 'Java鍩虹璇硶', 1, 26000, 1756304300044, NOW()),
-- SpringBoot瀹炴垬锛? 棰樺 4锛堟纭巼 80%锛岃嚜鍔ㄩ厤缃?璧锋渚濊禆鐔熺粌锛岃嚜瀹氫箟starter鍋舵湁澶辫锛?(1, 'SpringBoot瀹炴垬', 1, 33000, 1756304400045, NOW()),
(1, 'SpringBoot瀹炴垬', 1, 36000, 1756304500046, NOW()),
(1, 'SpringBoot瀹炴垬', 0, 68000, 1756304600047, NOW()),
(1, 'SpringBoot瀹炴垬', 1, 31000, 1756304700048, NOW()),
(1, 'SpringBoot瀹炴垬', 1, 29000, 1756304800049, NOW()),
-- Redis缂撳瓨锛? 棰樺 3锛堟纭巼 75%锛屾暟鎹粨鏋?鎸佷箙鍖栨帉鎻″皻鍙紝缂撳瓨绌块€?鍑荤┛鏂规鍋舵贩娣嗭級
(1, 'Redis缂撳瓨', 1, 38000, 1756304900050, NOW()),
(1, 'Redis缂撳瓨', 1, 42000, 1756305000051, NOW()),
(1, 'Redis缂撳瓨', 0, 75000, 1756305100052, NOW()),
(1, 'Redis缂撳瓨', 1, 35000, 1756305200053, NOW());
