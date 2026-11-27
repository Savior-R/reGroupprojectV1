<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import {
  createQuestion,
  listCategories,
  listQuestions,
  updateQuestion,
} from './api'
import type { CategoryView, QuestionStatus, QuestionView } from './api'

/** M5 question bank (04 §9): category rail, question table, drawer editor, category picker. */

const router = useRouter()

const categories = ref<CategoryView[]>([])
const questions = ref<QuestionView[]>([])
const selectedQuestion = ref<QuestionView | null>(null)
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(true)
const errorMessage = ref('')

const categoryFilter = ref('')
const statusFilter = ref<'' | QuestionStatus>('')
const keyword = ref('')

const drawerVisible = ref(false)
const saving = ref(false)
const drawerError = ref('')
const editingQuestionId = ref<string | null>(null)
const questionForm = ref({
  categoryId: '',
  stem: '',
  answerPoints: '',
  status: 'active' as QuestionStatus,
})

const pickerVisible = ref(false)
const pickerKeyword = ref('')
const pickerPage = ref(1)
const pickerSize = 8

const activeCategories = computed(() =>
  categories.value.filter((category) => category.status === 'active'),
)
const filteredPickerCategories = computed(() => {
  const text = pickerKeyword.value.trim().toLowerCase()
  if (!text) {
    return activeCategories.value
  }
  return activeCategories.value.filter((category) =>
    category.name.toLowerCase().includes(text),
  )
})
const pagedPickerCategories = computed(() => {
  const start = (pickerPage.value - 1) * pickerSize
  return filteredPickerCategories.value.slice(start, start + pickerSize)
})
const selectedCategory = computed(() =>
  categories.value.find((category) => category.category_id === questionForm.value.categoryId),
)
const isEmptyBank = computed(
  () => total.value === 0 && !keyword.value.trim() && !categoryFilter.value && !statusFilter.value,
)

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

async function loadCategories(): Promise<void> {
  try {
    categories.value = await listCategories()
  } catch (error) {
    errorMessage.value = errorText(error, '类别加载失败。')
  }
}

async function loadQuestions(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listQuestions({
      categoryId: categoryFilter.value,
      status: statusFilter.value,
      keyword: keyword.value.trim(),
      page: page.value,
      size: size.value,
    })
    questions.value = result.items
    total.value = result.total
    const current = selectedQuestion.value
    if (!current || !result.items.some((item) => item.question_id === current.question_id)) {
      selectedQuestion.value = result.items[0] ?? null
    }
  } catch (error) {
    errorMessage.value = errorText(error, '题目加载失败。')
  } finally {
    loading.value = false
  }
}

function selectQuestion(question: QuestionView): void {
  selectedQuestion.value = question
}

function rowClassName({ row }: { row: QuestionView }): string {
  return row.question_id === selectedQuestion.value?.question_id ? 'question-row--selected' : ''
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

function search(): void {
  page.value = 1
  void loadQuestions()
}

function changePage(next: number): void {
  page.value = next
  void loadQuestions()
}

function selectCategory(categoryId: string): void {
  categoryFilter.value = categoryId
  search()
}

function openCreate(): void {
  editingQuestionId.value = null
  drawerError.value = ''
  questionForm.value = {
    categoryId: activeCategories.value[0]?.category_id ?? '',
    stem: '',
    answerPoints: '',
    status: 'active',
  }
  drawerVisible.value = true
}

function openEdit(question: QuestionView): void {
  editingQuestionId.value = question.question_id
  drawerError.value = ''
  questionForm.value = {
    categoryId: question.category_id,
    stem: question.stem,
    answerPoints: question.answer_points,
    status: question.status,
  }
  drawerVisible.value = true
}

function openPicker(): void {
  pickerKeyword.value = ''
  pickerPage.value = 1
  pickerVisible.value = true
}

function selectPickedCategory(category: CategoryView): void {
  questionForm.value.categoryId = category.category_id
  pickerVisible.value = false
}

async function saveQuestion(): Promise<void> {
  if (!questionForm.value.categoryId) {
    drawerError.value = '请选择启用中的类别。'
    return
  }
  if (!questionForm.value.stem.trim()) {
    drawerError.value = '题干不能为空，且不超过 500 字。'
    return
  }
  if (!questionForm.value.answerPoints.trim()) {
    drawerError.value = '答案要点不能为空，且不超过 2000 字。'
    return
  }
  saving.value = true
  drawerError.value = ''
  try {
    if (editingQuestionId.value) {
      await updateQuestion(editingQuestionId.value, {
        category_id: questionForm.value.categoryId,
        stem: questionForm.value.stem.trim(),
        answer_points: questionForm.value.answerPoints.trim(),
        status: questionForm.value.status,
      })
      ElMessage.success('题目已更新')
    } else {
      await createQuestion({
        category_id: questionForm.value.categoryId,
        stem: questionForm.value.stem.trim(),
        answer_points: questionForm.value.answerPoints.trim(),
      })
      ElMessage.success('题目已创建')
    }
    drawerVisible.value = false
    await Promise.all([loadCategories(), loadQuestions()])
  } catch (error) {
    drawerError.value = errorText(error, '保存失败。')
  } finally {
    saving.value = false
  }
}

async function toggleQuestion(question: QuestionView): Promise<void> {
  const next: QuestionStatus = question.status === 'active' ? 'inactive' : 'active'
  if (next === 'inactive') {
    try {
      await ElMessageBox.confirm(
        '停用后，M3 结构化面试选题不再展示该题；已发布任务快照不受影响。',
        '停用题目',
        { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  try {
    await updateQuestion(question.question_id, { status: next })
    ElMessage.success(next === 'active' ? '题目已启用' : '题目已停用')
    await loadQuestions()
  } catch (error) {
    errorMessage.value = errorText(error, '状态更新失败。')
  }
}

function openCategoryManagement(): void {
  void router.push('/teacher/question-bank/categories')
}

onMounted(async () => {
  await Promise.all([loadCategories(), loadQuestions()])
})
</script>

<template>
  <div class="question-bank">
    <header class="question-bank__header">
      <div>
        <h1>结构化面试题库</h1>
        <p>本校题库：类别与题目管理；停用题目不会进入 M3 选题列表。</p>
      </div>
      <div class="question-bank__actions">
        <ElButton @click="openCategoryManagement">类别管理</ElButton>
        <ElButton type="primary" @click="openCreate">新增题目</ElButton>
      </div>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <div class="bank-layout">
      <aside class="category-rail">
        <button
          type="button"
          class="category-rail__item"
          :class="{ 'category-rail__item--active': categoryFilter === '' }"
          @click="selectCategory('')"
        >
          全部类别
          <span>{{ categories.length }}</span>
        </button>
        <button
          v-for="category in categories"
          :key="category.category_id"
          type="button"
          class="category-rail__item"
          :class="{
            'category-rail__item--active': categoryFilter === category.category_id,
            'category-rail__item--muted': category.status !== 'active',
          }"
          @click="selectCategory(category.category_id)"
        >
          {{ category.name }}
          <span v-if="category.status !== 'active'">停用</span>
        </button>
        <p v-if="categories.length === 0" class="category-rail__empty">暂无类别</p>
      </aside>

      <ElCard shadow="never" class="question-panel">
        <div class="filters">
          <ElSelect v-model="categoryFilter" placeholder="全部类别" clearable @change="search">
            <ElOption
              v-for="category in categories"
              :key="category.category_id"
              :label="category.name"
              :value="category.category_id"
            />
          </ElSelect>
          <ElSelect v-model="statusFilter" placeholder="全部状态" clearable @change="search">
            <ElOption label="启用" value="active" />
            <ElOption label="停用" value="inactive" />
          </ElSelect>
          <ElInput
            v-model="keyword"
            class="filters__keyword"
            placeholder="搜索题干 / 答案要点"
            clearable
            @keyup.enter="search"
          />
          <ElButton type="primary" plain @click="search">搜索</ElButton>
        </div>

        <p v-if="loading" class="state">加载中…</p>
        <ElEmpty
          v-else-if="isEmptyBank"
          description="题库为空。先添加题目或等待种子初始化；题库为空时 M3 不能创建结构化面试任务。"
        >
          <ElButton type="primary" plain @click="openCreate">添加题目</ElButton>
        </ElEmpty>
        <ElEmpty v-else-if="questions.length === 0" description="没有匹配的题目" />

        <template v-else>
          <ElTable
            :data="questions"
            stripe
            row-key="question_id"
            :row-class-name="rowClassName"
            @row-click="selectQuestion"
          >
            <ElTableColumn label="题干 / 答案要点" min-width="360">
              <template #default="{ row }">
                <p class="stem">{{ row.stem }}</p>
                <p class="answer-points">答案要点：{{ row.answer_points }}</p>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="category_name" label="类别" width="150" />
            <ElTableColumn label="状态" width="90">
              <template #default="{ row }">
                <ElTag :type="row.status === 'active' ? 'success' : 'info'">
                  {{ row.status === 'active' ? '启用' : '停用' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <ElButton size="small" text @click="openEdit(row as QuestionView)">编辑</ElButton>
                <ElButton
                  size="small"
                  text
                  :type="row.status === 'active' ? 'danger' : 'success'"
                  @click="toggleQuestion(row as QuestionView)"
                >
                  {{ row.status === 'active' ? '停用' : '启用' }}
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
          <ElPagination
            v-if="total > size"
            class="pagination"
            layout="prev, pager, next, total"
            :total="total"
            :page-size="size"
            :current-page="page"
            @current-change="changePage"
          />
        </template>
      </ElCard>

      <aside class="preview-panel">
        <template v-if="selectedQuestion">
          <div class="preview-panel__head">
            <strong>题目预览</strong>
            <ElTag :type="selectedQuestion.status === 'active' ? 'success' : 'info'" effect="plain">
              {{ selectedQuestion.status === 'active' ? '启用' : '停用' }}
            </ElTag>
          </div>
          <p class="preview-panel__stem">{{ selectedQuestion.stem }}</p>
          <div class="preview-panel__tags">
            <ElTag effect="plain">{{ selectedQuestion.category_name }}</ElTag>
          </div>
          <section class="preview-section">
            <h3>答案要点</h3>
            <p class="preview-answer">{{ selectedQuestion.answer_points }}</p>
            <p class="preview-note">答案要点仅教师可见，学生端不会返回。</p>
          </section>
          <dl class="preview-meta">
            <div>
              <dt>创建时间</dt>
              <dd>{{ formatDateTime(selectedQuestion.created_at) }}</dd>
            </div>
            <div>
              <dt>更新时间</dt>
              <dd>{{ formatDateTime(selectedQuestion.updated_at) }}</dd>
            </div>
          </dl>
          <ElButton class="preview-panel__edit" plain @click="openEdit(selectedQuestion)">
            编辑题目
          </ElButton>
        </template>
        <ElEmpty v-else description="选择题目查看答案要点预览" :image-size="72" />
      </aside>
    </div>

    <ElDrawer v-model="drawerVisible" :title="editingQuestionId ? '编辑题目' : '新增题目'" size="520px">
      <ElAlert v-if="drawerError" type="error" :closable="false" show-icon>
        <template #title>{{ drawerError }}</template>
      </ElAlert>
      <div class="drawer-form">
        <div class="field">
          <span>类别</span>
          <div class="category-pick">
            <ElButton class="category-pick__button" @click="openPicker">
              {{ selectedCategory?.name ?? '选择类别' }}
            </ElButton>
            <ElTag v-if="selectedCategory && selectedCategory.status !== 'active'" type="info">
              已停用
            </ElTag>
          </div>
          <p class="field__hint">类别引用使用独立窗口；停用类别不可选。</p>
        </div>
        <label class="field">
          <span>题干（≤ 500 字）</span>
          <ElInput
            v-model="questionForm.stem"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
          />
        </label>
        <label class="field">
          <span>答案要点（必填，≤ 2000 字）</span>
          <ElInput
            v-model="questionForm.answerPoints"
            type="textarea"
            :rows="6"
            maxlength="2000"
            show-word-limit
          />
        </label>
        <div v-if="editingQuestionId" class="field">
          <span>状态</span>
          <ElRadioGroup v-model="questionForm.status">
            <ElRadioButton value="active">启用</ElRadioButton>
            <ElRadioButton value="inactive">停用</ElRadioButton>
          </ElRadioGroup>
        </div>
      </div>
      <template #footer>
        <ElButton @click="drawerVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveQuestion">保存</ElButton>
      </template>
    </ElDrawer>

    <ElDialog v-model="pickerVisible" title="选择类别" width="560px">
      <ElInput
        v-model="pickerKeyword"
        class="picker__search"
        placeholder="搜索类别名称"
        clearable
        @input="pickerPage = 1"
      />
      <ElEmpty v-if="pagedPickerCategories.length === 0" description="没有匹配的启用类别" />
      <ul v-else class="picker__list">
        <li v-for="category in pagedPickerCategories" :key="category.category_id">
          <div>
            <strong>{{ category.name }}</strong>
            <p>排序 {{ category.sort_order }}</p>
          </div>
          <ElButton size="small" type="primary" plain @click="selectPickedCategory(category)">
            选择
          </ElButton>
        </li>
      </ul>
      <ElPagination
        v-if="filteredPickerCategories.length > pickerSize"
        class="picker__pagination"
        layout="prev, pager, next, total"
        :total="filteredPickerCategories.length"
        :page-size="pickerSize"
        :current-page="pickerPage"
        @current-change="pickerPage = $event"
      />
      <template #footer>
        <ElButton @click="pickerVisible = false">取消</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.question-bank {
  max-width: 1220px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.question-bank__header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.question-bank__header h1 {
  margin: 0 0 6px;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
}

.question-bank__header p {
  margin: 0;
  color: var(--m1-text-secondary);
}

.question-bank__actions {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.bank-layout {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

.question-panel :deep(.question-row--selected > td) {
  background: var(--m1-primary-soft);
}

.question-panel :deep(.el-table__row) {
  cursor: pointer;
}

.preview-panel {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--m1-border);
  border-radius: 12px;
  background: var(--m1-bg-surface);
  position: sticky;
  top: 16px;
}

.preview-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--m1-border);
}

.preview-panel__head strong {
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
}

.preview-panel__stem {
  margin: 0;
  color: var(--m1-text-primary);
  line-height: 1.65;
  white-space: pre-wrap;
}

.preview-panel__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.preview-section h3 {
  margin: 0 0 6px;
  color: var(--m1-text-secondary);
  font-size: 12px;
  font-weight: 500;
}

.preview-answer {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--m1-primary-soft);
  color: var(--m1-text-primary);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.preview-note {
  margin: 8px 0 0;
  color: var(--m1-text-placeholder);
  font-size: 12px;
}

.preview-meta {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-top: 12px;
  border-top: 1px solid var(--m1-border);
}

.preview-meta div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
}

.preview-meta dt {
  color: var(--m1-text-secondary);
}

.preview-meta dd {
  margin: 0;
  color: var(--m1-text-regular);
}

.preview-panel__edit {
  justify-self: start;
}

.category-rail {
  display: grid;
  gap: 4px;
  padding: 8px;
  border: 1px solid var(--m1-border);
  border-radius: 12px;
  background: var(--m1-bg-surface);
}

.category-rail__item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--m1-text-regular);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.category-rail__item:hover {
  background: var(--m1-bg-hover);
}

.category-rail__item--active {
  background: var(--m1-primary-soft);
  color: var(--m1-primary);
}

.category-rail__item--muted {
  color: var(--m1-text-placeholder);
}

.category-rail__item span {
  color: var(--m1-text-secondary);
  font-size: 11px;
}

.category-rail__empty {
  margin: 6px;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.question-panel {
  min-width: 0;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.filters__keyword {
  max-width: 280px;
}

.state {
  color: var(--m1-text-secondary);
}

.stem {
  margin: 0;
  color: var(--m1-text-primary);
  white-space: pre-wrap;
}

.answer-points {
  margin: 4px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
  white-space: pre-wrap;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.drawer-form {
  display: grid;
  gap: 16px;
}

.field {
  display: grid;
  gap: 6px;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.field__hint {
  margin: 0;
  color: var(--m1-text-placeholder);
}

.category-pick {
  display: flex;
  align-items: center;
  gap: 8px;
}

.category-pick__button {
  justify-content: flex-start;
  width: 100%;
}

.picker__search {
  margin-bottom: 12px;
}

.picker__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 8px;
}

.picker__list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--m1-border);
  border-radius: 10px;
}

.picker__list p {
  margin: 4px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.picker__pagination {
  margin-top: 12px;
  justify-content: flex-end;
}

@media (max-width: 1200px) {
  .bank-layout {
    grid-template-columns: 200px minmax(0, 1fr);
  }

  .preview-panel {
    display: none;
  }
}

@media (max-width: 860px) {
  .bank-layout {
    grid-template-columns: 1fr;
  }
}
</style>
