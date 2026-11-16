import type { AxiosInstance } from 'axios'
import { ApiError } from '../../types/api'
import type { CategoryView, PageResult, QuestionStatus, QuestionView } from './api'
import type { TrainingLevel, TrainingTemplateView } from '../training/types'

/**
 * Dev-only mock for the M5 / M6 preview page. It replaces the Axios methods on the
 * shared client so the real pages can run without PostgreSQL / Spring Boot.
 * Nothing here is imported by the production entry (`src/main.ts`).
 */

const nowIso = () => new Date().toISOString()
const isoDaysAgo = (days: number) => new Date(Date.now() - days * 86_400_000).toISOString()

let categories: CategoryView[] = [
  category('cat-1', '教学设计', 1, 'active', 12),
  category('cat-2', '课堂管理', 2, 'active', 8),
  category('cat-3', '教育理念', 3, 'inactive', 5),
]

let questions: QuestionView[] = [
  question('q-1', 'cat-1', '如何设计一堂课的导入环节？',
    '贴近学生经验；制造认知冲突；明确本课目标；控制在 5 分钟内。', 'active', 6),
  question('q-2', 'cat-1', '怎样围绕教学目标设计课堂提问？',
    '问题指向目标；由浅入深；留出思考时间；追问生成性回答。', 'active', 5),
  question('q-3', 'cat-2', '课堂出现学生走神时你会怎么处理？',
    '先观察原因；用提问或互动拉回注意；课后单独沟通；避免当众羞辱。', 'active', 4),
  question('q-4', 'cat-2', '如何建立课堂常规并保持一致性？',
    '与学生共同约定；明确可观察的行为标准；及时反馈；长期稳定执行。', 'active', 9),
  question('q-5', 'cat-3', '谈谈你理解的教育公平。',
    '关注个体差异；提供适合的支持；评价多元；不放弃任何学生。', 'inactive', 3),
  question('q-6', 'cat-3', '教师应如何对待学生的错误？',
    '把错误当作学习资源；分析原因；提供改进路径；保护学习信心。', 'active', 2),
]

let templates: TrainingTemplateView[] = [
  template('tpl-1', 'task-demo-001', 'course-demo-001', 'progressive',
    '荷塘月色', 900, 1200, 3, true, 2),
  template('tpl-2', 'task-demo-002', 'course-demo-002', 'mock_exam',
    '故都的秋', 1200, 1200, 3, false, 5),
]

function category(
  id: string,
  name: string,
  sortOrder: number,
  status: QuestionStatus,
  ageDays: number,
): CategoryView {
  return {
    category_id: id,
    name,
    sort_order: sortOrder,
    status,
    created_at: isoDaysAgo(ageDays),
    updated_at: isoDaysAgo(Math.max(0, ageDays - 1)),
  }
}

function question(
  id: string,
  categoryId: string,
  stem: string,
  answerPoints: string,
  status: QuestionStatus,
  ageDays: number,
): QuestionView {
  return {
    question_id: id,
    category_id: categoryId,
    category_name: categories.find((item) => item.category_id === categoryId)?.name ?? '',
    stem,
    answer_points: answerPoints,
    status,
    created_by: 'teacher-demo',
    updated_by: 'teacher-demo',
    created_at: isoDaysAgo(ageDays),
    updated_at: isoDaysAgo(Math.max(0, ageDays - 1)),
  }
}

function template(
  id: string,
  sourceTaskId: string,
  courseId: string,
  level: TrainingLevel,
  topic: string,
  duration: number,
  maxDuration: number,
  students: number,
  hasMaterial: boolean,
  ageDays: number,
): TrainingTemplateView {
  return {
    template_id: id,
    source_task_id: sourceTaskId,
    source_task_config_version: 3,
    course_id: courseId,
    training_level: level,
    status: 'published',
    topic,
    duration_seconds: duration,
    max_duration_seconds: maxDuration,
    virtual_student_count: students,
    has_reference_material: hasMaterial,
    weights: { content: 30, expression: 20, interaction: 20, board: 15, time: 15 },
    published_at: isoDaysAgo(ageDays),
    unpublished_at: null,
    created_at: isoDaysAgo(ageDays),
  }
}

function pageOf<T>(
  items: T[],
  params: Record<string, unknown>,
  defaultSize: number,
): PageResult<T> {
  const page = Number(params.page ?? 1)
  const size = Number(params.size ?? defaultSize)
  const start = (page - 1) * size
  return { items: items.slice(start, start + size), total: items.length, page, size }
}

function fail(code: string, message: string, status = 422): never {
  throw new ApiError({ code, message, status })
}

function reply<T>(data: T) {
  return Promise.resolve({
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: {},
  })
}

function pathOf(url: string): string {
  return url.split('?')[0]
}

async function handleGet(url: string, params: Record<string, unknown>) {
  const path = pathOf(url)
  await delay()

  if (path === '/question-bank/categories') {
    const status = String(params.status ?? '')
    const keyword = String(params.keyword ?? '').trim()
    return categories.filter((item) =>
      (!status || item.status === status) &&
      (!keyword || item.name.includes(keyword)))
  }

  if (path === '/question-bank/questions') {
    const categoryId = String(params.category_id ?? '')
    const status = String(params.status ?? '')
    const keyword = String(params.keyword ?? '').trim()
    const filtered = questions.filter((item) =>
      (!categoryId || item.category_id === categoryId) &&
      (!status || item.status === status) &&
      (!keyword ||
        item.stem.includes(keyword) ||
        item.answer_points.includes(keyword)))
    return pageOf(filtered, params, 20)
  }

  if (path === '/training/templates') {
    const level = String(params.training_level ?? '')
    const courseId = String(params.course_id ?? '')
    const filtered = templates.filter((item) =>
      (!level || item.training_level === level) &&
      (!courseId || item.course_id === courseId))
    return pageOf(filtered, params, 50)
  }

  fail('NOT_FOUND', `Mock 未实现：GET ${path}`, 404)
}

async function handlePost(url: string, body: Record<string, unknown>) {
  const path = pathOf(url)
  await delay()

  if (path === '/question-bank/categories') {
    const name = String(body.name ?? '').trim()
    if (!name) fail('VALIDATION_ERROR', '类别名称必填')
    if (categories.some((item) => item.name === name)) {
      fail('DUPLICATE_RESOURCE', '类别名称已存在', 409)
    }
    const created = category(
      `cat-${Date.now()}`, name, Number(body.sort_order ?? 1), 'active', 0)
    categories = [...categories, created]
    return created
  }

  if (path === '/question-bank/questions') {
    const categoryId = String(body.category_id ?? '')
    const stem = String(body.stem ?? '').trim()
    const answerPoints = String(body.answer_points ?? '').trim()
    if (!categories.some((item) => item.category_id === categoryId)) {
      fail('RESOURCE_NOT_FOUND', '类别不存在', 404)
    }
    if (!stem || !answerPoints) fail('ANSWER_POINTS_MISSING', '题干与答案要点必填')
    const created = question(
      `q-${Date.now()}`, categoryId, stem, answerPoints, 'active', 0)
    questions = [...questions, created]
    return created
  }

  if (path === '/training/templates') {
    const sourceTaskId = String(body.source_task_id ?? '').trim()
    const courseId = String(body.course_id ?? '').trim()
    const level = String(body.training_level ?? 'progressive') as TrainingLevel
    if (!sourceTaskId || !courseId) fail('VALIDATION_ERROR', '源任务与目标课程必填')
    const id = `tpl-${Date.now()}`
    const topic = sourceTaskId.includes('task-demo-001') ? '荷塘月色'
      : `训练模板 ${templates.length + 1}`
    const created = template(
      id, sourceTaskId, courseId, level, topic,
      level === 'mock_exam' ? 1200 : 900, 1200, 3, false, 0)
    templates = [...templates, created]
    return created
  }

  const unpublish = path.match(/^\/training\/templates\/([^/]+)\/unpublish$/)
  if (unpublish) {
    const id = decodeURIComponent(unpublish[1])
    const found = templates.find((item) => item.template_id === id)
    if (!found) fail('RESOURCE_NOT_FOUND', '模板不存在', 404)
    found.status = 'unpublished'
    found.unpublished_at = nowIso()
    return undefined
  }

  fail('NOT_FOUND', `Mock 未实现：POST ${path}`, 404)
}

async function handlePatch(url: string, body: Record<string, unknown>) {
  const path = pathOf(url)
  await delay()

  const categoryMatch = path.match(/^\/question-bank\/categories\/([^/]+)$/)
  if (categoryMatch) {
    const id = decodeURIComponent(categoryMatch[1])
    const found = categories.find((item) => item.category_id === id)
    if (!found) fail('RESOURCE_NOT_FOUND', '类别不存在', 404)
    if (body.name !== undefined) found.name = String(body.name).trim()
    if (body.sort_order !== undefined) found.sort_order = Number(body.sort_order)
    if (body.status !== undefined) found.status = body.status as QuestionStatus
    found.updated_at = nowIso()
    questions.forEach((item) => {
      if (item.category_id === id) item.category_name = found.name
    })
    return found
  }

  const questionMatch = path.match(/^\/question-bank\/questions\/([^/]+)$/)
  if (questionMatch) {
    const id = decodeURIComponent(questionMatch[1])
    const found = questions.find((item) => item.question_id === id)
    if (!found) fail('RESOURCE_NOT_FOUND', '题目不存在', 404)
    if (body.category_id !== undefined) {
      found.category_id = String(body.category_id)
      found.category_name =
        categories.find((item) => item.category_id === found.category_id)?.name ?? ''
    }
    if (body.stem !== undefined) found.stem = String(body.stem).trim()
    if (body.answer_points !== undefined) {
      const answerPoints = String(body.answer_points).trim()
      if (!answerPoints) fail('ANSWER_POINTS_MISSING', '答案要点不能为空')
      found.answer_points = answerPoints
    }
    if (body.status !== undefined) found.status = body.status as QuestionStatus
    found.updated_at = nowIso()
    return found
  }

  fail('NOT_FOUND', `Mock 未实现：PATCH ${path}`, 404)
}

function delay(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 120))
}

export function installMockApi(client: AxiosInstance): void {
  const mock = client as unknown as {
    get: (url: string, config?: { params?: Record<string, unknown> }) => Promise<unknown>
    post: (url: string, body?: Record<string, unknown>) => Promise<unknown>
    patch: (url: string, body?: Record<string, unknown>) => Promise<unknown>
    delete: (url: string) => Promise<unknown>
  }

  mock.get = (url, config) => handleGet(url, config?.params ?? {}).then(reply)
  mock.post = (url, body) => handlePost(url, body ?? {}).then(reply)
  mock.patch = (url, body) => handlePatch(url, body ?? {}).then(reply)
  mock.delete = () => Promise.resolve({
    data: undefined, status: 200, statusText: 'OK', headers: {}, config: {},
  })
}
