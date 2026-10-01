package com.smartstaff.service;

import com.smartstaff.dto.request.QuestionEditRequest;
import com.smartstaff.dto.response.AssessmentQuestionResponse;

import java.util.UUID;

/** Changes to a single question of a version nobody has taken yet. */
public interface AssessmentQuestionService {

    /** Regenerate just this question's slot through the generation pipeline,
     *  into the same version. 409 once the version has attempts. */
    AssessmentQuestionResponse regenerate(UUID questionId);

    /** HR edit; re-validated (coding questions in the sandbox) before saving.
     *  409 once the version has attempts; 400 if the edit fails validation. */
    AssessmentQuestionResponse edit(UUID questionId, QuestionEditRequest req);
}
