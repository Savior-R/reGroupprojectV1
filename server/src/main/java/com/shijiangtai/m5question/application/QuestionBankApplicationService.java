package com.shijiangtai.m5question.application;

import com.shijiangtai.m5question.application.command.CreateCategoryCommand;
import com.shijiangtai.m5question.application.command.CreateQuestionCommand;
import com.shijiangtai.m5question.application.command.QuestionQuery;
import com.shijiangtai.m5question.application.command.UpdateCategoryCommand;
import com.shijiangtai.m5question.application.command.UpdateQuestionCommand;
import com.shijiangtai.m5question.application.view.DemoInitView;
import com.shijiangtai.m5question.domain.CategoryView;
import com.shijiangtai.m5question.domain.QuestionView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.PageResult;
import java.util.List;
import java.util.UUID;

/** Teacher / admin facing question-bank management (04 §5–§7.1). */
public interface QuestionBankApplicationService {

    List<CategoryView> listCategories(CurrentUser user, String status, String keyword);

    CategoryView createCategory(CurrentUser user, CreateCategoryCommand command);

    CategoryView updateCategory(CurrentUser user, UUID categoryId, UpdateCategoryCommand command);

    PageResult<QuestionView> listQuestions(CurrentUser user, QuestionQuery query);

    QuestionView createQuestion(CurrentUser user, CreateQuestionCommand command);

    QuestionView updateQuestion(CurrentUser user, UUID questionId, UpdateQuestionCommand command);

    DemoInitView initDemo(CurrentUser user);
}
