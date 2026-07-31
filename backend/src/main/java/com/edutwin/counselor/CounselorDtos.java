package com.edutwin.counselor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CounselorDtos {
    private CounselorDtos() {}

    public record StudentSummary(
            UUID studentId,
            String username,
            String displayName,
            String studentNumber,
            String college,
            String major,
            int cohortYear,
            String className,
            int courseCount,
            BigDecimal totalCredits,
            BigDecimal averageScorePercentage,
            BigDecimal averageMastery,
            int highRiskCourseCount,
            OffsetDateTime lastActivityAt) {}

    public record StudentPage(List<StudentSummary> items, int total, int page, int size) {}

    public record CourseStatistics(
            UUID courseId,
            String code,
            String title,
            String termLabel,
            BigDecimal credits,
            List<String> instructors,
            String enrollmentStatus,
            int completedLessons,
            int totalLessons,
            int submittedAssessments,
            int publishedAssessments,
            BigDecimal scorePercentage,
            BigDecimal averageMastery,
            String riskBand,
            BigDecimal riskProbability,
            OffsetDateTime lastActivityAt) {}

    public record StudentOverview(StudentSummary student, List<CourseStatistics> courses) {}

    public record ClassComparison(
            String className,
            int studentCount,
            int activeCourseEnrollments,
            BigDecimal averageScorePercentage,
            BigDecimal averageMastery,
            BigDecimal averageRiskProbability,
            int lowRiskCourseCount,
            int mediumRiskCourseCount,
            int highRiskCourseCount,
            BigDecimal planCompletionRate) {}

    public record ClassRiskTrendPoint(
            String className, LocalDate weekStart, BigDecimal averageRiskProbability) {}

    public record ClassComparisonResult(
            List<ClassComparison> classes,
            String period,
            List<ClassRiskTrendPoint> riskTrend) {}
}
