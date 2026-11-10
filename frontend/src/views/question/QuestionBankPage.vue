<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import {
  createCategory,
  createQuestion,
  listCategories,
  listQuestions,
  updateCategory,
  updateQuestion,
} from './api'
import type { CategoryView, QuestionStatus, QuestionView } from './api'

/** M5 question bank management (04 §9): categories + questions, disable with protection. */

const categories = ref<CategoryView[]>([])
const questions = ref<QuestionView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(true)
const errorMessage = ref('')

const categoryFilter = ref('')
const statusFilter = ref<'' | QuestionStatus>('')
const keyword = ref('')

const questionDialog = ref(false)
const questionSaving = ref(false)
const questionError = ref('')
const editingQuestionId = ref<string | null>(null)
const questionForm = ref({ categoryId: '', stem: '', answerPoints: '' })

const categoryDialog = ref(false)
const categorySaving = ref(false)
const categoryError = ref('')
const newCategory = ref({ name: '', sortOrder: 1 as number | null })
const editingCategory = ref<CategoryView | null>(null)
const editCategoryForm = ref({ name: '', sortOrder: 1 as number | null })

const activeCategories = () =>
  categories.value.filter((category) => category.status === 'active')

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
  } catch (error) {
    errorMessage.value = errorText(error, '题目加载失败。')
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void loadQuestions()
}

function changePage(next: number): void {
  page.value = next
  void loadQuestions()
}

function openCreateQuestion(): void {
  editingQuestionId.value = null
  questionError.value = ''
  questionForm.value = {
    categoryId: activeCategories()[0]?.category_id ?? '',
    stem: '',
    answerPoints: '',
  }
  questionDialog.value = true
}

function openEditQuestion(question: QuestionView): void {
  editingQuestionId.value = question.question_id
  questionError.value = ''
  questionForm.value = {
    categoryId: question.category_id,
    stem: question.stem,
    answerPoints: question.answer_points,
  }
  questionDialog.value = true
}

async function saveQuestion(): Promise<void> {
  if (!questionForm.value.categoryId) {
    questionError.value = '请选择启用中的类别。'
    return
  }
  if (!questionForm.value.stem.trim() || !questionForm.value.answerPoints.trim()) {
    questionError.value = '题干与答案要点必须填写。'
    return
  }
  questionSaving.value = true
  questionError.value = ''
  try {
    if (editingQuestionId.value) {
      await updateQuestion(editingQuestionId.value, {
        category_id: questionForm.value.categoryId,
        stem: questionForm.value.stem.trim(),
        answer_points: questionForm.value.answerPoints.trim(),
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
    questionDialog.value = false
    await Promise.all([loadCategories(), loadQuestions()])
  } catch (error) {
    questionError.value = errorText(error, '保存失败。')
  } finally {
    questionSaving.value = false
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

async function addCategory(): Promise<void> {
  if (!newCategory.value.name.trim()) {
    categoryError.value = '类别名称必填。'
    return
  }
  categorySaving.value = true
  categoryError.value = ''
  try {
    await createCategory({
      name: newCategory.value.name.trim(),
      sort_order: newCategory.value.sortOrder,
    })
    newCategory.value = { name: '', sortOrder: 1 }
    ElMessage.success('类别已创建')
    await loadCategories()
  } catch (error) {
    categoryError.value = errorText(error, '类别创建失败。')
  } finally {
    categorySaving.value = false
  }
}

function openEditCategory(category: CategoryView): void {
  editingCategory.value = category
  editCategoryForm.value = { name: category.name, sortOrder: category.sort_order }
  categoryError.value = ''
}

async function saveCategory(): Promise<void> {
  if (!editingCategory.value) {
    return
  }
  if (!editCategoryForm.value.name.trim()) {
    categoryError.value = '类别名称必填。'
    return
  }
  categorySaving.value = true
  categoryError.value = ''
  try {
    await updateCategory(editingCategory.value.category_id, {
      name: editCategoryForm.value.name.trim(),
      sort_order: editCategoryForm.value.sortOrder,
    })
    editingCategory.value = null
    ElMessage.success('类别已更新')
    await Promise.all([loadCategories(), loadQuestions()])
  } catch (error) {
    categoryError.value = errorText(error, '类别更新失败。')
  } finally {
    categorySaving.value = false
  }
}

async function toggleCategory(category: CategoryView): Promise<void> {
  const next: QuestionStatus = category.status === 'active' ? 'inactive' : 'active'
  try {
    await updateCategory(category.category_id, { status: next })
    ElMessage.success(next === 'active' ? '类别已启用' : '类别已停用')
    await Promise.all([loadCategories(), loadQuestions()])
  } catch (error) {
    categoryError.value = errorText(error, '类别状态更新失败。')
  }
}

function openCategoryDialog(): void {
  categoryError.value = ''
  editingCategory.value = null
  categoryDialog.value = true
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
        <ElButton @click="openCategoryDialog">类别管理</ElButton>
        <ElButton type="primary" @click="openCreateQuestion">新增题目</ElButton>
      </div>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <ElCard shadow="never">
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
      <ElTable v-else :data="questions" stripe>
        <ElTableColumn label="题干" min-width="320">
          <template #default="{ row }">
            <p class="stem">{{ row.stem }}</p>
            <p class="answer-points">答案要点：{{ row.answer_points }}</p>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="category_name" label="类别" width="150" />
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 'active' ? 'success' : 'info'">
              {{ row.status === 'active' ? '启用' : '停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" text @click="openEditQuestion(row as QuestionView)">编辑</ElButton>
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
    </ElCard>

    <ElDialog v-model="questionDialog" :title="editingQuestionId ? '编辑题目' : '新增题目'" width="640px">
      <ElAlert v-if="questionError" type="error" :closable="false" show-icon>
        <template #title>{{ questionError }}</template>
      </ElAlert>
      <div class="dialog-form">
        <label>
          <span>类别</span>
          <ElSelect v-model="questionForm.categoryId" class="dialog-form__control">
            <ElOption
              v-for="category in activeCategories()"
              :key="category.category_id"
              :label="category.name"
              :value="category.category_id"
            />
          </ElSelect>
        </label>
        <label>
          <span>题干</span>
          <ElInput v-model="questionForm.stem" type="textarea" :rows="3" maxlength="2000" />
        </label>
        <label>
          <span>答案要点（必填）</span>
          <ElInput v-model="questionForm.answerPoints" type="textarea" :rows="4" maxlength="4000" />
        </label>
      </div>
      <template #footer>
        <ElButton @click="questionDialog = false">取消</ElButton>
        <ElButton type="primary" :loading="questionSaving" @click="saveQuestion">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="categoryDialog" title="类别管理" width="720px">
      <ElAlert v-if="categoryError" type="error" :closable="false" show-icon>
        <template #title>{{ categoryError }}</template>
      </ElAlert>

      <div class="category-add">
        <ElInput v-model="newCategory.name" placeholder="新类别名称" maxlength="50" />
        <ElInputNumber v-model="newCategory.sortOrder" :min="1" :max="9999" />
        <ElButton type="primary" :loading="categorySaving" @click="addCategory">添加</ElButton>
      </div>

      <ElTable :data="categories" stripe>
        <ElTableColumn prop="name" label="名称" min-width="160">
          <template #default="{ row }">
            <template v-if="editingCategory?.category_id === row.category_id">
              <ElInput v-model="editCategoryForm.name" size="small" />
            </template>
            <template v-else>{{ row.name }}</template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="排序" width="110">
          <template #default="{ row }">
            <ElInputNumber
              v-if="editingCategory?.category_id === row.category_id"
              v-model="editCategoryForm.sortOrder"
              size="small"
              :min="1"
              :max="9999"
            />
            <template v-else>{{ row.sort_order }}</template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="row.status === 'active' ? 'success' : 'info'">
              {{ row.status === 'active' ? '启用' : '停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="editingCategory?.category_id === row.category_id">
              <ElButton size="small" type="primary" text :loading="categorySaving" @click="saveCategory">
                保存
              </ElButton>
              <ElButton size="small" text @click="editingCategory = null">取消</ElButton>
            </template>
            <template v-else>
              <ElButton size="small" text @click="openEditCategory(row as CategoryView)">编辑</ElButton>
              <ElButton size="small" text @click="toggleCategory(row as CategoryView)">
                {{ row.status === 'active' ? '停用' : '启用' }}
              </ElButton>
            </template>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElDialog>
  </div>
</template>

<style scoped>
.question-bank {
  max-width: 1180px;
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
}

.question-bank__header p {
  margin: 0;
  color: #8fa3c0;
}

.question-bank__actions {
  display: flex;
  align-items: flex-start;
  gap: 8px;
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
  color: #8fa3c0;
}

.stem {
  margin: 0;
  white-space: pre-wrap;
}

.answer-points {
  margin: 4px 0 0;
  color: #8fa3c0;
  font-size: 12px;
  white-space: pre-wrap;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.dialog-form {
  display: grid;
  gap: 12px;
}

.dialog-form label {
  display: grid;
  gap: 6px;
  color: #8fa3c0;
  font-size: 12px;
}

.dialog-form__control {
  width: 100%;
}

.category-add {
  display: grid;
  grid-template-columns: 1fr 120px auto;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
