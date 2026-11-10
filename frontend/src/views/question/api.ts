import { apiClient } from '../../api/client'

/** M5 API module lives with the views until the shared API owner registers it. */

export type QuestionStatus = 'active' | 'inactive'

export interface CategoryView {
  category_id: string
  name: string
  sort_order: number
  status: QuestionStatus
  created_at: string
  updated_at: string
}

export interface QuestionView {
  question_id: string
  category_id: string
  category_name: string
  stem: string
  answer_points: string
  status: QuestionStatus
  created_by: string
  updated_by: string
  created_at: string
  updated_at: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export function listCategories(status?: string, keyword?: string): Promise<CategoryView[]> {
  return apiClient
    .get<CategoryView[]>('/question-bank/categories', {
      params: { status: status || undefined, keyword: keyword || undefined },
    })
    .then((response) => response.data)
}

export function createCategory(payload: {
  name: string
  sort_order: number | null
}): Promise<CategoryView> {
  return apiClient
    .post<CategoryView>('/question-bank/categories', payload)
    .then((response) => response.data)
}

export function updateCategory(
  categoryId: string,
  payload: { name?: string; sort_order?: number | null; status?: QuestionStatus },
): Promise<CategoryView> {
  return apiClient
    .patch<CategoryView>(
      `/question-bank/categories/${encodeURIComponent(categoryId)}`,
      payload,
    )
    .then((response) => response.data)
}

export function listQuestions(params: {
  categoryId?: string
  status?: string
  keyword?: string
  page?: number
  size?: number
}): Promise<PageResult<QuestionView>> {
  return apiClient
    .get<PageResult<QuestionView>>('/question-bank/questions', {
      params: {
        category_id: params.categoryId || undefined,
        status: params.status || undefined,
        keyword: params.keyword || undefined,
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    })
    .then((response) => response.data)
}

export function createQuestion(payload: {
  category_id: string
  stem: string
  answer_points: string
}): Promise<QuestionView> {
  return apiClient
    .post<QuestionView>('/question-bank/questions', payload)
    .then((response) => response.data)
}

export function updateQuestion(
  questionId: string,
  payload: {
    category_id?: string
    stem?: string
    answer_points?: string
    status?: QuestionStatus
  },
): Promise<QuestionView> {
  return apiClient
    .patch<QuestionView>(
      `/question-bank/questions/${encodeURIComponent(questionId)}`,
      payload,
    )
    .then((response) => response.data)
}
