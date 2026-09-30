package com.tutor.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 企业私有知识库切片实体（课程大纲、退费政策、伴学协议、自研真题等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnterpriseKnowledgeChunk {
    /** 唯一分块 ID：如 policy_refund_01_0 */
    private String chunkId;
    /** 归属文档 ID */
    private String docId;
    /** 分类：SERVICE_POLICY / COURSE_SYLLABUS / COURSE_QUESTION */
    private String category;
    /** 关联知识点或课程标签 */
    private String kp;
    /** 切片标题 */
    private String title;
    /** 切片正文（包含完整内容） */
    private String text;
    /** 权威来源依据 */
    private String source;
    /** 内容 SHA-256 哈希指纹（用于增量更新比对，防重复计算） */
    private String contentHash;
    /** 1024 维 Dense 密集语义向量 */
    private List<Float> embedding;
}
