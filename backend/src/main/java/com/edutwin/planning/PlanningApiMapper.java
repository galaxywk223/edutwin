package com.edutwin.planning;

import com.edutwin.api.model.PlanLifecycle;
import com.edutwin.api.model.PlanPracticeTask;
import com.edutwin.api.model.PlanTaskProjection;

public final class PlanningApiMapper {
    private PlanningApiMapper() {}

    public static PlanLifecycle toApi(PlanningDtos.PlanLifecycle source) {
        PlanLifecycle target = new PlanLifecycle();
        target.setPlanId(source.planId());
        target.setCourseId(source.courseId());
        target.setStudentId(source.studentId());
        target.setVersion(source.version());
        target.setStatus(PlanLifecycle.StatusEnum.fromValue(source.status()));
        target.setValidUntil(source.validUntil());
        target.setExpiredAt(source.expiredAt());
        target.setTasks(source.tasks().stream().map(PlanningApiMapper::toApi).toList());
        return target;
    }

    public static PlanPracticeTask toApi(PlanningDtos.PracticeTask source) {
        PlanPracticeTask target = new PlanPracticeTask();
        target.setPlanTaskId(source.planTaskId());
        target.setPlanId(source.planId());
        target.setCourseId(source.courseId());
        target.setQuestionId(source.questionId());
        target.setQuestionTitle(source.questionTitle());
        target.setPrompt(source.prompt());
        target.setAnswerType(source.answerType());
        target.setOptionsJson(source.optionsJson());
        target.setSkillId(source.skillId());
        target.setSkillName(source.skillName());
        target.setTargetCount(source.targetCount());
        target.setCompletedCount(source.completedCount());
        return target;
    }

    private static PlanTaskProjection toApi(PlanningDtos.PlanTaskProjection source) {
        PlanTaskProjection target = new PlanTaskProjection();
        target.setPlanTaskId(source.planTaskId());
        target.setPlanId(source.planId());
        target.setQuestionId(source.questionId());
        target.setQuestionTitle(source.questionTitle());
        target.setSkillId(source.skillId());
        target.setSkillName(source.skillName());
        target.setTaskType(PlanTaskProjection.TaskTypeEnum.fromValue(source.taskType()));
        target.setReasonCode(source.reasonCode());
        target.setTargetCount(source.targetCount());
        target.setCompletedCount(source.completedCount());
        target.setStatus(PlanTaskProjection.StatusEnum.fromValue(source.status()));
        target.setDueAt(source.dueAt());
        target.setStartedAt(source.startedAt());
        target.setCompletedAt(source.completedAt());
        target.setSkippedAt(source.skippedAt());
        target.setSkipReason(source.skipReason());
        target.setPracticePath(source.practicePath());
        return target;
    }
}
