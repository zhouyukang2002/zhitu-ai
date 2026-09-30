package com.tutor.biz.controller;

import com.tutor.biz.service.CorpusIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语料摄入入口（研发侧）：MD 语料库 → tutor_biz 业务库。
 * 与引擎侧的 ES 同步（POST :8080/api/admin/corpus/sync）配合使用：
 * 课程介绍（引擎入 ES 语义索引）、课程主数据与题库（本服务入 MySQL）同源于 MD 语料。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class CorpusAdminController {

    private final CorpusIngestService corpusIngestService;
    private final com.tutor.biz.service.CourseCatalogService courseCatalogService;

    @PostMapping("/corpus/sync")
    public Map<String, Object> sync() {
        Map<String, Object> result = corpusIngestService.syncAll();
        courseCatalogService.reload();
        return result;
    }
}
