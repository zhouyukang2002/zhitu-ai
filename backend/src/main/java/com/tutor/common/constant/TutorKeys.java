package com.tutor.common.constant;

/**
 * Redis key 前缀统一约定：{@code tutor:<域>:<id>[:<后缀>]}。
 * 域前缀集中定义，避免跨服务读写同一份数据时字面量漂移
 * （如 session 状态与转人工连败计数同域，曾分别在两个类里重复定义）。
 */
public final class TutorKeys {

    /** 会话域：状态 / 槽位 / 转人工连败计数（tutor:session:{sessionId}[:slots|:degraded_streak]） */
    public static final String SESSION = "tutor:session:";
    /** 记忆域：会话记忆与超限摘要（tutor:memory:{sessionId}[:summary]） */
    public static final String MEMORY = "tutor:memory:";
    /** 批改幂等（tutor:grade:{exerciseId}:{userId}） */
    public static final String GRADE_IDEMPOTENT = "tutor:grade:";
    /** 语义缓存（tutor:semantic_cache:{hash}，索引集合 tutor:semantic_cache_keys） */
    public static final String SEMANTIC_CACHE = "tutor:semantic_cache:";
    public static final String SEMANTIC_CACHE_INDEX = "tutor:semantic_cache_keys";
    /** 用户技能矩阵画像（tutor:user_skill_matrix:{userId}） */
    public static final String USER_SKILL_MATRIX = "tutor:user_skill_matrix:";

    private TutorKeys() {
    }
}
