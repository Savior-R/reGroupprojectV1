<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
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
import { createCategory, listCategories, updateCategory } from './api'
import type { CategoryView, QuestionStatus } from './api'

/** M5 category repository (04 §9): search, status filter, add / edit, reorder, disable. */

const router = useRouter()

const categories = ref<CategoryView[]>([])
const loading = ref(true)
const errorMessage = ref('')
const keyword = ref('')
const statusFilter = ref<'' | QuestionStatus>('')
const page = ref(1)
const size = ref(10)

const dialogVisible = ref(false)
const saving = ref(false)
const dialogError = ref('')
const editingId = ref<string | null>(null)
const form = ref({ name: '' })
const orderingId = ref<string | null>(null)

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return categories.value
    .filter((category) => !statusFilter.value || category.status === statusFilter.value)
    .filter((category) => !text || category.name.toLowerCase().includes(text))
})
const paged = computed(() => {
  const start = (page.value - 1) * size.value
  return filtered.value.slice(start, start + size.value)
})

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    categories.value = await listCategories()
    if (page.value > 1 && paged.value.length === 0) {
      page.value = 1
    }
  } catch (error) {
    errorMessage.value = errorText(error, '类别加载失败，请重试。')
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
}

function openCreate(): void {
  editingId.value = null
  dialogError.value = ''
  form.value = { name: '' }
  dialogVisible.value = true
}

function openEdit(category: CategoryView): void {
  editingId.value = category.category_id
  dialogError.value = ''
  form.value = { name: category.name }
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!form.value.name.trim()) {
    dialogError.value = '类别名称必填，且不超过 50 字。'
    return
  }
  saving.value = true
  dialogError.value = ''
  try {
    if (editingId.value) {
      await updateCategory(editingId.value, { name: form.value.name.trim() })
      ElMessage.success('类别已更新')
    } else {
      const nextSort = categories.value.reduce(
        (max, category) => Math.max(max, category.sort_order),
        0,
      ) + 1
      await createCategory({ name: form.value.name.trim(), sort_order: nextSort })
      ElMessage.success('类别已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    dialogError.value = errorText(error, '保存失败。')
  } finally {
    saving.value = false
  }
}

async function move(category: CategoryView, direction: -1 | 1): Promise<void> {
  const ordered = [...categories.value].sort(
    (left, right) =>
      left.sort_order - right.sort_order || left.name.localeCompare(right.name, 'zh-CN'),
  )
  const index = ordered.findIndex((item) => item.category_id === category.category_id)
  const target = ordered[index + direction]
  if (index < 0 || !target) {
    return
  }
  orderingId.value = category.category_id
  errorMessage.value = ''
  try {
    const currentSort = index + 1
    const targetSort = index + direction + 1
    await updateCategory(category.category_id, { sort_order: targetSort })
    await updateCategory(target.category_id, { sort_order: currentSort })
    await load()
  } catch (error) {
    errorMessage.value = errorText(error, '排序更新失败。')
  } finally {
    orderingId.value = null
  }
}

async function toggleStatus(category: CategoryView): Promise<void> {
  const next: QuestionStatus = category.status === 'active' ? 'inactive' : 'active'
  if (next === 'inactive') {
    try {
      await ElMessageBox.confirm(
        '停用类别后，M3 新任务选题不再展示该类别下的题目；已发布任务快照不受影响。类别下仍有题目时不能删除，本轮只提供停用。',
        `停用「${category.name}」`,
        { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  try {
    await updateCategory(category.category_id, { status: next })
    ElMessage.success(next === 'active' ? '类别已启用' : '类别已停用')
    await load()
  } catch (error) {
    errorMessage.value = errorText(error, '状态更新失败。')
  }
}

function back(): void {
  void router.push('/teacher/question-bank')
}

onMounted(load)
</script>

<template>
  <div class="category-page">
    <div class="category-page__top">
      <ElButton text @click="back">← 结构化面试题库</ElButton>
      <ElButton type="primary" @click="openCreate">新增类别</ElButton>
    </div>

    <header>
      <h1>类别管理</h1>
      <p>类别名称校内唯一，1—50 字；排序使用上 / 下移动；有题目的类别只能停用。</p>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <ElCard shadow="never">
      <div class="filters">
        <ElInput
          v-model="keyword"
          class="filters__keyword"
          placeholder="搜索类别名称"
          clearable
          @input="search"
        />
        <ElSelect v-model="statusFilter" placeholder="全部状态" clearable @change="search">
          <ElOption label="启用" value="active" />
          <ElOption label="停用" value="inactive" />
        </ElSelect>
      </div>

      <p v-if="loading" class="state">加载中…</p>
      <ElEmpty v-else-if="filtered.length === 0" description="没有匹配的类别" />
      <template v-else>
        <ElTable :data="paged" stripe>
          <ElTableColumn label="排序" width="150">
            <template #default="{ row, $index }">
              <div class="sort-cell">
                <span>{{ row.sort_order }}</span>
                <ElButton
                  size="small"
                  text
                  :disabled="($index === 0 && page === 1) || orderingId !== null"
                  @click="move(row as CategoryView, -1)"
                >
                  上移
                </ElButton>
                <ElButton
                  size="small"
                  text
                  :disabled="($index === paged.length - 1 && page * size >= filtered.length) || orderingId !== null"
                  @click="move(row as CategoryView, 1)"
                >
                  下移
                </ElButton>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="name" label="名称" min-width="200" />
          <ElTableColumn label="题目状态" width="100">
            <template #default="{ row }">
              <ElTag :type="row.status === 'active' ? 'success' : 'info'">
                {{ row.status === 'active' ? '启用' : '停用' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <ElButton size="small" text @click="openEdit(row as CategoryView)">编辑</ElButton>
              <ElButton
                size="small"
                text
                :type="row.status === 'active' ? 'danger' : 'success'"
                @click="toggleStatus(row as CategoryView)"
              >
                {{ row.status === 'active' ? '停用' : '启用' }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElPagination
          v-if="filtered.length > size"
          class="pagination"
          layout="prev, pager, next, total"
          :total="filtered.length"
          :page-size="size"
          :current-page="page"
          @current-change="page = $event"
        />
      </template>
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="editingId ? '编辑类别' : '新增类别'" width="480px">
      <ElAlert v-if="dialogError" type="error" :closable="false" show-icon>
        <template #title>{{ dialogError }}</template>
      </ElAlert>
      <label class="field">
        <span>类别名称（≤ 50 字）</span>
        <ElInput v-model="form.name" maxlength="50" show-word-limit />
      </label>
      <p class="dialog-hint">排序请保存后在列表中用上移 / 下移调整。</p>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="save">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.category-page {
  max-width: 980px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.category-page__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.category-page header h1 {
  margin: 0 0 6px;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
}

.category-page header p {
  margin: 0;
  color: var(--m1-text-secondary);
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

.sort-cell {
  display: flex;
  align-items: center;
  gap: 2px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.field {
  display: grid;
  gap: 6px;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.dialog-hint {
  margin: 10px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}
</style>
