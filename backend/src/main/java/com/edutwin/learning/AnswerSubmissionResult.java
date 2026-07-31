package com.edutwin.learning;

import com.edutwin.api.model.AnalysisJob;

public record AnswerSubmissionResult(AnalysisJob job, boolean replayed) {}
