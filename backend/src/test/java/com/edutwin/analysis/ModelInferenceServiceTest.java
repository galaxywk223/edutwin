package com.edutwin.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edutwin.analysis.AnalysisWorkItemRepository.Answer;
import com.edutwin.analysis.AnalysisWorkItemRepository.SkillState;
import com.edutwin.analysis.AnalysisWorkItemRepository.WorkItem;
import com.edutwin.api.model.ModelVersionRef;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelInferenceServiceTest {

    private static final UUID SKILL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID QUESTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Test
    void onlineBktUsesFixedVersionsAndRaisesMasteryAfterCorrectAnswer() {
        ModelInferenceService.KnowledgeResult result = ModelInferenceService.onlineBkt(workItem(true, null));

        BigDecimal mastery = result.mastery().getFirst().probability();
        assertTrue(mastery.compareTo(new BigDecimal("0.20000000")) > 0);
        assertProbability(mastery);
        assertProbability(result.nextCorrectProbability());
        assertEquals(ModelVersionRef.FamilyEnum.BKT, result.masteryModel().getFamily());
        assertEquals(ModelVersionRef.FamilyEnum.BKT, result.nextModel().getFamily());
        assertEquals("online-bkt-course", result.masteryModel().getModelName());
        assertEquals("online-bkt-next", result.nextModel().getModelName());
        assertEquals("online-bkt-course-v1", result.masteryModel().getModelVersion());
        assertEquals("online-bkt-next-v1", result.nextModel().getModelVersion());
    }

    @Test
    void onlineBktLowersExistingMasteryAfterIncorrectAnswerAndKeepsProbabilitiesBounded() {
        BigDecimal prior = new BigDecimal("0.80000000");
        ModelInferenceService.KnowledgeResult result = ModelInferenceService.onlineBkt(workItem(false, prior));

        BigDecimal mastery = result.mastery().getFirst().probability();
        assertTrue(mastery.compareTo(prior) < 0);
        assertProbability(mastery);
        assertProbability(result.nextCorrectProbability());
    }

    private static WorkItem workItem(boolean correct, BigDecimal priorMastery) {
        Map<UUID, SkillState> skills = priorMastery == null
                ? Map.of()
                : Map.of(SKILL_ID, new SkillState(
                        SKILL_ID,
                        "语义知识点",
                        priorMastery,
                        priorMastery,
                        "previous-mastery-v1",
                        "previous-next-v1"));
        Answer answer = new Answer(
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                QUESTION_ID,
                correct,
                1_000,
                1,
                OffsetDateTime.parse("2026-07-13T00:00:00Z"),
                "synthetic-demo-v4",
                "ONLINE_BKT");
        return new WorkItem(
                null,
                null,
                answer,
                List.of(SKILL_ID),
                null,
                skills,
                List.of(),
                List.of(),
                Map.of());
    }

    private static void assertProbability(BigDecimal value) {
        assertTrue(value.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(value.compareTo(BigDecimal.ONE) <= 0);
    }
}
