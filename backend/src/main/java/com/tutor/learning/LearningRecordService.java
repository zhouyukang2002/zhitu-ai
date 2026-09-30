package com.tutor.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tutor.learning.entity.LearningRecordEntity;
import com.tutor.learning.mapper.LearningRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习记录服务：认知诊断的数据源。
 * 批改后自动追加记录（数据闭环：练习→批改→记录→下次诊断），替代一次性 Mock。
 */
@Service
@RequiredArgsConstructor
public class LearningRecordService {

    private final LearningRecordMapper mapper;

    public List<LearningRecordEntity> recordsForUser(Long userId, int limit) {
        List<LearningRecordEntity> list = mapper.selectList(new LambdaQueryWrapper<LearningRecordEntity>()
                .eq(LearningRecordEntity::getUserId, userId)
                .orderByDesc(LearningRecordEntity::getTs)
                .last("LIMIT " + Math.max(1, limit)));
        // 诊断按时间正序统计
        return list.stream().sorted(java.util.Comparator.comparingLong(LearningRecordEntity::getTs)).toList();
    }

    /** 批改后追加答题记录（timeMs 未知时记 0，诊断时忽略） */
    public void appendBatch(Long userId, List<RecordItem> items) {
        long now = System.currentTimeMillis();
        for (RecordItem item : items) {
            LearningRecordEntity e = new LearningRecordEntity();
            e.setUserId(userId);
            e.setKnowledgePoint(item.kp());
            e.setCorrect(item.correct());
            e.setTimeMs(item.timeMs() == null ? 0 : item.timeMs().intValue());
            e.setTs(now++);
            e.setCreatedAt(LocalDateTime.now());
            mapper.insert(e);
        }
    }

    public record RecordItem(String kp, Boolean correct, Long timeMs) {
    }
}
