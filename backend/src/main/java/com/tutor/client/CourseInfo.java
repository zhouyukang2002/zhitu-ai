package com.tutor.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 课程信息（与 Mock 课程服务的静态面对应，含推荐理由字段）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CourseInfo {

    private String courseId;
    private String name;
    private Integer price;
    private List<String> tags;
    /** 推荐理由（推荐智能体产出/数据面预置） */
    private String reason;
}
