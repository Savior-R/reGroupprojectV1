package com.shijiangtai.m5question.application;

import com.shijiangtai.m5question.domain.CategoryView;
import com.shijiangtai.m5question.domain.QuestionView;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.List;
import java.util.UUID;

/**
 * Read-only M3 selection contract (04 §7).
 *
 * <p>No {@code CurrentUser}: M3 calls this after its own permission checks, with the
 * course school id, so a teacher can only ever see their school's questions.
 */
public interface QuestionBankQueryService {

    List<CategoryView> listActiveCategories(SchoolId schoolId);

    PageResult<QuestionView> listActiveQuestions(
            SchoolId schoolId, UUID categoryId, String keyword, int page, int size);

    QuestionView getQuestion(SchoolId schoolId, UUID questionId);

    /** Returns every found question in the given school, including disabled ones. */
    List<QuestionView> getQuestions(SchoolId schoolId, List<UUID> questionIds);
}
