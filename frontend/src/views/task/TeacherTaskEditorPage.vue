<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCheckbox,
  ElCheckboxGroup,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { getCourse } from '../../api/courses'
import type { CourseDetailView } from '../../api/courses'
import {
  closeTask as closeTaskApi,
  createTask,
  getTeacherTask,
  updateTask,
} from '../../api/tasks'
import type {
  TaskMode,
  TaskReferenceMaterial,
  TaskStatus,
  TeacherTaskView,
  TaskWritePayload,
} from '../../api/tasks'
import { listCategories, listQuestions } from '../question/api'
import type { CategoryView, QuestionView } from '../question/api'
import VirtualStudentEditor from '../training/components/VirtualStudentEditor.vue'
import { defaultStudents } from '../training/students'
import type { EditableStudent } from '../training/types'
import { clientId } from '../../utils/id'
import { ApiError } from '../../types/api'

/**
 * 教师任务创建 / 编辑（05C §9.1—§9.6）。
 *
 * 创建即发布；已发布任务在无试讲 / 提交时仍可编辑；自由 / 情景必须带参考材料。
 * 面试模式的题目来自 M5 题库，时长由所选题目总时长决定。
 */

const route = useRoute()
const router = useRouter()

const courseId = computed(() => String(route.params.courseId ?? ''))
const taskId = computed(() => String(route.params.taskId ?? ''))
const isNew = computed(() => !taskId.value)

const course = ref<CourseDetailView | null>(null)
const task = ref<TeacherTaskView | null>(null)
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const fieldErrors = ref<string[]>([])

const students = ref<EditableStudent[]>([])
const studentIds = new Map<string, string>()

const material = ref<TaskReferenceMaterial | null>(null)
const materialFile = ref<File | null>(null)
const materialInput = ref<HTMLInputElement | null>(null)

const categories = ref<CategoryView[]>([])
const questions = ref<QuestionView[]>([])
const categoryFilter = ref('')
const questionKeyword = ref('')

const form = reactive({
  mode: 'free' as TaskMode,
  topic: '',
  textbook_version: '',
  duration_recommended_seconds: 900,
  duration_max_seconds: 1200,
  rubric_weights: { content: 30, expression: 20, interaction: 20, board: 15, time: 15 } as Record<string, number>,
  scenario_role: '',
  scenario_situation: '',
  question_total_duration: 300,
  question_ids: [] as string[],
})

const STATUS_LABELS: Record<TaskStatus, string> = {
  published: '已发布',
  closed: '已关闭',
  deleted: '已删除',
}

const DIMENSION_LABELS: Record<string, string> = {
  content: '内容',
  expression: '表达',
  interaction: '互动',
  board: '板书',
  time: '时间',
}

const readOnly = computed(() => Boolean(task.value && task.value.status !== 'published'))
const isInterview = computed(() => form.mode === 'interview')
const dimensions = computed(() =>
  isInterview.value ? ['content', 'expression', 'time'] : ['content', 'expression', 'interaction', 'board', 'time'],
)
const weightSum = computed(() => dimensions.value.reduce((sum, key) => sum + (form.rubric_weights[key] ?? 0), 0))
const selectedCount = computed(() => form.question_ids.length)

const filteredQuestions = computed(() =>
  questions.value.filter((question) => {
    if (categoryFilter.value && question.category_id !== categoryFilter.value) {
      return false
    }
    const keyword = questionKeyword.value.trim()
    return !keyword || question.stem.includes(keyword) || question.answer_points.includes(keyword)
  }),
)

function errorText(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) {
    return fallback
  }
  const fields = error.details?.fields
  if (Array.isArray(fields)) {
    const parts = fields
      .map((item) => {
        if (item && typeof item === 'object' && 'message' in item) {
          return String((item as { message?: unknown }).message ?? '')
        }
        return ''
      })
      .filter(Boolean)
    if (parts.length > 0) {
      return `${error.message}（${parts.join('；')}）`
    }
  }
  return `${error.message}（${error.code}）`
}

function pickMaterial(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  if (file) {
    materialFile.value = file
    ElMessage.success(`已选择 ${file.name}，保存后上传`)
  }
  input.value = ''
}

function onModeChange(mode: TaskMode): void {
  if (readOnly.value) {
    return
  }
  if (mode === 'interview') {
    form.rubric_weights = { content: 40, expression: 30, time: 30 }
    form.question_total_duration = form.question_total_duration || 300
    form.duration_recommended_seconds = form.question_total_duration
    form.duration_max_seconds = form.question_total_duration
    students.value = []
    materialFile.value = null
  } else {
    form.rubric_weights = { content: 30, expression: 20, interaction: 20, board: 15, time: 15 }
    if (students.value.length === 0) {
      students.value = defaultStudents()
    }
    if (form.duration_recommended_seconds < 1) {
      form.duration_recommended_seconds = 900
    }
    if (form.duration_max_seconds < form.duration_recommended_seconds) {
      form.duration_max_seconds = 1200
    }
  }
  if (mode !== 'scenario') {
    form.scenario_role = ''
    form.scenario_situation = ''
  }
}

function syncInterviewDuration(): void {
  if (isInterview.value) {
    form.duration_recommended_seconds = form.question_total_duration
    form.duration_max_seconds = form.question_total_duration
  }
}

function validate(): boolean {
  fieldErrors.value = []
  if (!form.topic.trim()) {
    fieldErrors.value.push('课题不能为空')
  }
  if (!isInterview.value && !form.textbook_version.trim()) {
    fieldErrors.value.push('教材版本不能为空')
  }
  if (!isInterview.value && students.value.length === 0) {
    fieldErrors.value.push('至少添加 1 名虚拟学生')
  }
  if (!isInterview.value && !material.value && !materialFile.value) {
    fieldErrors.value.push('发布前必须上传参考材料')
  }
  if (weightSum.value !== 100) {
    fieldErrors.value.push(`权重和必须为 100，当前 ${weightSum.value}`)
  }
  if (isInterview.value && (form.question_ids.length < 1 || form.question_ids.length > 10)) {
    fieldErrors.value.push('面试题目数量必须为 1—10')
  }
  if (form.duration_recommended_seconds < 1 || form.duration_max_seconds < form.duration_recommended_seconds) {
    fieldErrors.value.push('时长配置非法')
  }
  return fieldErrors.value.length === 0
}

function buildPayload(): TaskWritePayload {
  const payload: TaskWritePayload = {
    mode: form.mode,
    topic: form.topic.trim(),
    textbook_version: isInterview.value ? null : form.textbook_version.trim() || null,
    duration_recommended_seconds: form.duration_recommended_seconds,
    duration_max_seconds: form.duration_max_seconds,
    rubric_weights: { ...form.rubric_weights },
    scenario: form.mode === 'scenario'
      ? { role: form.scenario_role.trim() || null, situation: form.scenario_situation.trim() || null }
      : null,
    virtual_students: isInterview.value
      ? []
      : students.value.map((student) => ({
          virtual_student_id: studentIds.get(student.key),
          name: student.name,
          avatar: student.avatar,
          personality_text: student.personality_text,
          activity: student.activity,
          question_rate: student.question_rate,
          ability: student.ability,
          attention_baseline: student.attention_baseline,
          discipline_probability: student.discipline_probability,
          seat_no: student.seat_no,
          sort_order: student.sort_order,
        })),
    question_config: isInterview.value
      ? { question_ids: [...form.question_ids], total_duration_seconds: form.question_total_duration }
      : null,
  }
  return payload
}

async function saveTask(): Promise<void> {
  if (!validate()) {
    errorMessage.value = '请先修正表单中的问题'
    return
  }
  try {
    await ElMessageBox.confirm(
      isNew.value
        ? '创建后任务会立即发布给课程内全部在课学生。确认发布？'
        : '保存后会重新生成配置快照与分配名单；已有试讲 / 提交时不能修改。确认保存？',
      isNew.value ? '发布任务' : '保存任务修改',
      { confirmButtonText: isNew.value ? '发布' : '保存', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  saving.value = true
  errorMessage.value = ''
  try {
    if (task.value) {
      task.value = await updateTask(task.value.task_id, buildPayload(), materialFile.value)
      material.value = task.value.reference_material
      materialFile.value = null
      ElMessage.success('任务已更新')
      await loadTask()
    } else {
      const saved = await createTask(courseId.value, buildPayload(), materialFile.value)
      task.value = saved
      ElMessage.success('任务已发布')
      await router.push(`/teacher/courses/${courseId.value}/tasks`)
    }
  } catch (error) {
    errorMessage.value = errorText(error, isNew.value ? '发布失败' : '保存失败')
  } finally {
    saving.value = false
  }
}

async function closeTask(): Promise<void> {
  if (!task.value) {
    return
  }
  try {
    await ElMessageBox.confirm('关闭后学生不能开始新的作答，已有提交仍可评分。确认关闭？', '关闭任务', {
      confirmButtonText: '关闭',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    task.value = await closeTaskApi(task.value.task_id)
    ElMessage.success('任务已关闭')
  } catch (error) {
    errorMessage.value = errorText(error, '关闭失败')
  }
}

async function loadTask(): Promise<void> {
  if (isNew.value) {
    return
  }
  try {
    const loaded = await getTeacherTask(taskId.value)
    task.value = loaded
    form.mode = loaded.mode
    form.topic = loaded.topic
    form.textbook_version = loaded.textbook_version ?? ''
    form.duration_recommended_seconds = loaded.duration_recommended_seconds
    form.duration_max_seconds = loaded.duration_max_seconds
    form.rubric_weights = { ...loaded.rubric_weights }
    form.scenario_role = loaded.scenario?.role ?? ''
    form.scenario_situation = loaded.scenario?.situation ?? ''
    form.question_total_duration = loaded.question_config?.total_duration_seconds ?? 300
    form.question_ids = [...(loaded.question_config?.question_ids ?? [])]
    material.value = loaded.reference_material
    materialFile.value = null

    studentIds.clear()
    students.value = loaded.virtual_students.map((student) => {
      const key = clientId()
      if (student.virtual_student_id) {
        studentIds.set(key, student.virtual_student_id)
      }
      return { ...student, personality_text: student.personality_text ?? '', key }
    })
  } catch (error) {
    errorMessage.value = errorText(error, '任务加载失败')
  }
}

async function loadCourse(): Promise<void> {
  try {
    course.value = await getCourse(courseId.value)
  } catch (error) {
    errorMessage.value = errorText(error, '课程加载失败')
  }
}

async function loadQuestionBank(): Promise<void> {
  try {
    const [categoryList, questionPage] = await Promise.all([
      listCategories('active'),
      listQuestions({ status: 'active', page: 1, size: 200 }),
    ])
    categories.value = categoryList
    questions.value = questionPage.items
  } catch (error) {
    // 题库加载失败不阻塞自由 / 情景任务；面试模式发布前会由后端校验。
    if (isInterview.value) {
      errorMessage.value = errorText(error, '题库加载失败')
    }
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await loadCourse()
    if (isNew.value) {
      students.value = defaultStudents()
    } else {
      await loadTask()
    }
    if (!readOnly.value) {
      await loadQuestionBank()
    }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="task-editor-page">
    <header class="task-editor-page__header">
      <div>
        <ElButton text @click="router.push(`/teacher/courses/${courseId}/tasks`)">
          ← 返回任务列表
        </ElButton>
        <h1>{{ isNew ? '新建任务' : task?.topic ?? '任务详情' }}</h1>
        <p>
          {{ course?.name ?? '课程' }}
          <template v-if="task"> · <ElTag size="small" type="info">{{ STATUS_LABELS[task.status] }}</ElTag></template>
        </p>
      </div>
      <div class="task-editor-page__actions">
        <template v-if="task">
          <ElButton @click="router.push(`/teacher/courses/${courseId}/tasks/${taskId}/grading`)">
            评分
          </ElButton>
          <ElButton @click="router.push(`/teacher/courses/${courseId}/tasks/${taskId}/stats`)">
            统计
          </ElButton>
          <ElButton v-if="task.status === 'published'" type="warning" @click="closeTask">关闭任务</ElButton>
        </template>
        <ElButton
          v-if="!readOnly"
          type="primary"
          :loading="saving"
          @click="saveTask"
        >
          {{ isNew ? '发布任务' : '保存修改' }}
        </ElButton>
      </div>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
      <ul v-if="fieldErrors.length" class="field-errors">
        <li v-for="item in fieldErrors" :key="item">{{ item }}</li>
      </ul>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else>
      <ElCard shadow="never">
        <template #header><h2>基本信息</h2></template>
        <div class="form-grid">
          <label class="field">
            <span>任务模式</span>
            <ElRadioGroup v-model="form.mode" :disabled="readOnly" @change="onModeChange(form.mode)">
              <ElRadioButton value="free">自由试讲</ElRadioButton>
              <ElRadioButton value="scenario">情景试讲</ElRadioButton>
              <ElRadioButton value="interview">结构化面试</ElRadioButton>
            </ElRadioGroup>
          </label>

          <label class="field">
            <span>课题</span>
            <ElInput v-model="form.topic" maxlength="100" show-word-limit :disabled="readOnly" placeholder="例如：荷塘月色" />
          </label>

          <label v-if="!isInterview" class="field">
            <span>教材版本</span>
            <ElInput v-model="form.textbook_version" maxlength="100" :disabled="readOnly" placeholder="例如：部编版" />
          </label>

          <template v-if="!isInterview">
            <label class="field">
              <span>建议时长（秒）</span>
              <ElInputNumber v-model="form.duration_recommended_seconds" :min="1" :max="3600" :disabled="readOnly" />
            </label>
            <label class="field">
              <span>最长时长（秒）</span>
              <ElInputNumber v-model="form.duration_max_seconds" :min="1" :max="3600" :disabled="readOnly" />
            </label>
          </template>

          <template v-if="isInterview">
            <label class="field">
              <span>面试总时长（秒）</span>
              <ElInputNumber
                v-model="form.question_total_duration"
                :min="60"
                :max="3600"
                :step="30"
                :disabled="readOnly"
                @change="syncInterviewDuration"
              />
            </label>
            <label class="field">
              <span>已选题目</span>
              <ElTag :type="selectedCount >= 1 && selectedCount <= 10 ? 'success' : 'danger'">
                {{ selectedCount }} / 10
              </ElTag>
            </label>
          </template>
        </div>

        <template v-if="form.mode === 'scenario'">
          <h3 class="section-title">情景设定</h3>
          <div class="form-grid">
            <label class="field">
              <span>角色</span>
              <ElInput v-model="form.scenario_role" :disabled="readOnly" placeholder="例如：班主任" />
            </label>
            <label class="field">
              <span>情境</span>
              <ElInput v-model="form.scenario_situation" :disabled="readOnly" placeholder="例如：开学第一课" />
            </label>
          </div>
        </template>
      </ElCard>

      <ElCard shadow="never">
        <template #header>
          <div class="card-head">
            <h2>评分权重</h2>
            <ElTag :type="weightSum === 100 ? 'success' : 'danger'">合计 {{ weightSum }} / 100</ElTag>
          </div>
        </template>
        <div class="weights">
          <label v-for="dimension in dimensions" :key="dimension" class="weight-field">
            <span>{{ DIMENSION_LABELS[dimension] ?? dimension }}</span>
            <ElInputNumber
              v-model="form.rubric_weights[dimension]"
              :min="0"
              :max="100"
              :step="5"
              :disabled="readOnly"
            />
          </label>
        </div>
        <p class="hint">权重只影响最终分计算；发布后不可修改。</p>
      </ElCard>

      <ElCard v-if="!isInterview" shadow="never">
        <template #header><h2>虚拟学生</h2></template>
        <VirtualStudentEditor v-if="!readOnly" v-model="students" />
        <ElTable v-else :data="students" size="small" stripe>
          <ElTableColumn prop="name" label="姓名" width="120" />
          <ElTableColumn prop="avatar" label="头像" width="110" />
          <ElTableColumn label="活跃度" width="100">
            <template #default="{ row }">{{ Math.round(row.activity * 100) }}%</template>
          </ElTableColumn>
          <ElTableColumn label="提问率" width="100">
            <template #default="{ row }">{{ Math.round(row.question_rate * 100) }}%</template>
          </ElTableColumn>
          <ElTableColumn prop="personality_text" label="个性文本" min-width="220" />
        </ElTable>
      </ElCard>

      <ElCard v-if="isInterview" shadow="never">
        <template #header>
          <div class="card-head">
            <h2>面试题目</h2>
            <ElTag type="info">来自 M5 题库 · 发布时校验启用状态</ElTag>
          </div>
        </template>

        <template v-if="readOnly">
          <ElEmpty v-if="!task?.published_questions?.length" description="没有已发布题目" />
          <ElTable v-else :data="task.published_questions" size="small" stripe>
            <ElTableColumn prop="order" label="#" width="60" />
            <ElTableColumn prop="stem" label="题干" min-width="260" />
            <ElTableColumn prop="category_name" label="类别" width="140" />
            <ElTableColumn prop="answer_points" label="答案要点" min-width="240" />
          </ElTable>
        </template>

        <template v-else>
          <div class="question-filters">
            <ElSelect v-model="categoryFilter" placeholder="全部类别" clearable>
              <ElOption
                v-for="category in categories"
                :key="category.category_id"
                :label="category.name"
                :value="category.category_id"
              />
            </ElSelect>
            <ElInput v-model="questionKeyword" placeholder="搜索题干 / 答案要点" clearable />
            <span class="hint">最多 10 道；已选 {{ selectedCount }} 道</span>
          </div>

          <ElCheckboxGroup v-model="form.question_ids" class="question-list">
            <div v-for="question in filteredQuestions" :key="question.question_id" class="question-item">
              <ElCheckbox
                :value="question.question_id"
                :disabled="!form.question_ids.includes(question.question_id) && form.question_ids.length >= 10"
              >
                <span class="question-item__body">
                  <strong>{{ question.stem }}</strong>
                  <span class="cell-sub">{{ question.category_name }}</span>
                </span>
              </ElCheckbox>
            </div>
          </ElCheckboxGroup>
          <ElEmpty v-if="filteredQuestions.length === 0" description="没有可用题目" />
        </template>
      </ElCard>

      <ElCard v-if="!isInterview" shadow="never">
        <template #header><h2>参考材料</h2></template>
        <div v-if="material" class="material-row">
          <div>
            <strong>{{ material.filename }}</strong>
            <p class="cell-sub">{{ material.char_count }} 字 · {{ material.mime }}</p>
            <p class="material-preview">{{ material.preview }}</p>
          </div>

        </div>
        <div v-else-if="materialFile" class="material-row">
          <div>
            <strong>{{ materialFile.name }}</strong>
            <p class="cell-sub">保存时上传并解析</p>
          </div>
          <ElButton v-if="!readOnly" size="small" @click="materialFile = null">取消</ElButton>
        </div>
        <p v-else class="hint">尚未上传参考材料；自由 / 情景任务发布前必须上传。</p>

        <div v-if="!readOnly" class="material-actions">
          <input ref="materialInput" type="file" accept=".txt,.md,text/plain" hidden @change="pickMaterial">
          <ElButton @click="materialInput?.click()">选择文件</ElButton>
          <span class="hint">支持 .txt / .md，上传后由后端解析文本。</span>
        </div>
      </ElCard>

      <div v-if="!readOnly" class="footer-actions">
        <ElButton type="primary" :loading="saving" @click="saveTask">
          {{ isNew ? '发布任务' : '保存修改' }}
        </ElButton>
      </div>
    </template>
  </div>
</template>

<style scoped>
.task-editor-page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.task-editor-page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.task-editor-page__header h1 {
  margin: 4px 0;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
  font-size: 24px;
}

.task-editor-page__header p {
  margin: 0;
  color: var(--m1-text-secondary);
  font-size: 13px;
}

.task-editor-page__actions,
.footer-actions,
.card-head,
.material-row,
.material-actions,
.question-filters {
  display: flex;
  align-items: center;
  gap: 10px;
}

.task-editor-page__actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.card-head {
  justify-content: space-between;
}

.card-head h2,
.section-title {
  margin: 0;
  color: var(--m1-text-primary);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
}

.field {
  display: grid;
  gap: 6px;
  color: var(--m1-text-regular);
  font-size: 13px;
}

.section-title {
  margin-top: 20px;
  font-size: 16px;
}

.weights {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.weight-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--m1-border);
  border-radius: var(--m1-radius-sm);
  background: var(--m1-bg-subtle);
  color: var(--m1-text-regular);
  font-size: 13px;
}

.hint,
.cell-sub {
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.question-filters {
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.question-list {
  display: grid;
  max-height: 420px;
  gap: 6px;
  overflow-y: auto;
}

.question-item {
  padding: 8px 10px;
  border: 1px solid var(--m1-border);
  border-radius: var(--m1-radius-sm);
}

.question-item:hover {
  background: var(--m1-bg-subtle);
}

.question-item__body {
  display: grid;
  gap: 2px;
}

.material-row {
  justify-content: space-between;
  padding: 10px 12px;
  border: 1px solid var(--m1-border);
  border-radius: var(--m1-radius-sm);
  background: var(--m1-bg-subtle);
}

.material-preview {
  max-height: 72px;
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.footer-actions {
  justify-content: flex-end;
  padding-bottom: 24px;
}

.field-errors {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 12px;
}

.state {
  color: var(--m1-text-secondary);
}
</style>
