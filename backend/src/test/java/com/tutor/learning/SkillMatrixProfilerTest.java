package com.tutor.learning;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillMatrixProfilerTest {

    @Test
    void testSkillProfile_AccuracyAndLowestKp() {
        SkillMatrixProfiler.SkillProfile profile = new SkillMatrixProfiler.SkillProfile(1001L);
        profile.updateSkill("Java 并发编程", true);
        profile.updateSkill("Java 并发编程", false);
        profile.updateSkill("MySQL 索引机制", true);
        profile.updateSkill("MySQL 索引机制", true);

        Assertions.assertEquals(4, profile.getTotalAnswers());
        Assertions.assertEquals(3, profile.getTotalCorrect());
        Assertions.assertEquals(0.75, profile.getOverallAccuracy());

        // Java 并发正确率 50%，MySQL 100%，最低掌握度应为 "Java 并发编程"
        Assertions.assertEquals("Java 并发编程", profile.getLowestMasteryKp());
    }
}
