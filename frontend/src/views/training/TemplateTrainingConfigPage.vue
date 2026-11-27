<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElMessage,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import {
  copyTemplateFromPublication,
  createTrainingSession,
  getStudentTemplate,
} from './api'
import { dimensionLabel, formatDuration, levelLabel } from './format'
import { defaultStudents, fromWireStudents, toWireStudent } from './students'
import VirtualStudentEditor from './components/VirtualStudentEditor.vue'
import type { EditableStudent, StudentTemplateDetail } from './types'

/** Teacher-publication config (08 §12.4); only the public config is shown, students are editable. */

const route = useRoute()
const router = useRouter()
const publicationId = computed(() => String(route.params.publicationId ?? ''))

const detail = ref<StudentTemplateDetail | null>(null)
const students = ref<EditableStudent[]>(defaultStudents())
const loading = ref(true)
const starting = ref(false)
const copying = ref(false)
const errorMessage = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await getStudentTemplate(publicationId.value)
    students.value = fromWireStudents(detail.value.virtual_students)
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '模板加载失败，请重试。'
  } finally {
    loading.value = false
  }
}

function back(): void {
  void router.push('/student/training')
}

async function start(): Promise<void> {
  if (!detail.value) {
    return
  }
  const invalid = students.value.find((student) => !student.name.trim())
  if (invalid) {
    errorMessage.value = '虚拟学生姓名不能为空。'
    return
  }
  starting.value = true
  errorMessage.value = ''
  try {
    const session = await createTrainingSession({
      template_id: null,
      publication_id: detail.value.publication_id,
      training_level: detail.value.training_level,
      course_id: null,
      topic: null,
      virtual_students: students.value.map(toWireStudent),
      material_id: null,
    })
    await router.push(`/student/training/sessions/${session.session_id}/classroom`)
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '训练创建失败，请重试。'
  } finally {
    starting.value = false
  }
}

async function copyToMine(): Promise<void> {
  if (!detail.value || detail.value.copied) {
    return
  }
  copying.value = true
  errorMessage.value = ''
  try {
    await copyTemplateFromPublication(detail.value.publication_id, null)
    detail.value = { ...detail.value, copied: true }
    ElMessage.success('已复制到我的模板')
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '复制模板失败，请重试。'
  } finally {
    copying.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="config-page">
    <div class="config-page__top">
      <ElButton text @click="back">← 返回训练</ElButton>
      <div class="config-page__actions">
        <ElButton
          v-if="detail && !detail.copied"
          :loading="copying"
          @click="copyToMine"
        >
          复制到我的模板
        </ElButton>
        <ElButton type="primary" :loading="starting" :disabled="!detail" @click="start">
          开始训练
        </ElButton>
      </div>
    </div>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else-if="detail">
      <ElCard shadow="never" class="summary-card">
        <div class="summary-card__header">
          <div>
            <h1>{{ detail.name }}</h1>
            <p>{{ detail.topic }} · {{ detail.course_name }}</p>
          </div>
          <div class="summary-card__tags">
            <ElTag v-if="detail.copied" type="success" effect="plain">已复制</ElTag>
            <ElTag effect="dark">训练 · {{ levelLabel(detail.training_level) }}</ElTag>
          </div>
        </div>
        <div class="summary-grid">
          <div><span>课程</span><strong>{{ detail.course_name }}</strong></div>
          <div>
            <span>时长</span>
            <strong>
              推荐 {{ formatDuration(detail.recommended_duration_ms) }} / 最长
              {{ formatDuration(detail.max_duration_ms) }}
            </strong>
          </div>
          <div><span>档位</span><strong>{{ levelLabel(detail.training_level) }}</strong></div>
          <div>
            <span>训练维度</span>
            <strong>{{ detail.dimensions.map(dimensionLabel).join(' / ') }}</strong>
          </div>
        </div>
        <p class="fixed-note">
          时长与权重由教师模板固定，不可在本次训练中修改；参考材料与权重对学生不可见。
          虚拟学生可在下方调整。
        </p>
      </ElCard>

      <ElCard shadow="never">
        <template #header>
          <h2>虚拟学生（可调整，1—30 人）</h2>
        </template>
        <VirtualStudentEditor v-model="students" />
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.config-page {
  max-width: 1000px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.config-page__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.config-page__actions {
  display: flex;
  gap: 8px;
}

.state {
  color: var(--m1-text-secondary);
}

.summary-card__header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.summary-card__header h1 {
  margin: 0 0 6px;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
}

.summary-card__header p {
  margin: 0;
  color: var(--m1-text-secondary);
}

.summary-card__tags {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.summary-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.summary-grid div {
  display: grid;
  gap: 4px;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.summary-grid strong {
  color: var(--m1-text-primary);
  font-size: 14px;
}

.fixed-note {
  margin: 16px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}
</style>
