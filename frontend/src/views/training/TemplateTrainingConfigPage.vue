<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElTag } from 'element-plus'
import { ApiError } from '../../types/api'
import { createTrainingSession, getStudentTemplate } from './api'
import { formatDuration, levelLabel } from './format'
import { defaultStudents, fromWireStudents, toWireStudent } from './students'
import VirtualStudentEditor from './components/VirtualStudentEditor.vue'
import type { EditableStudent, StudentTemplateDetail } from './types'

/** Template training configuration (08 §12.3): public config only; students are editable. */

const route = useRoute()
const router = useRouter()
const templateId = computed(() => String(route.params.templateId ?? ''))

const detail = ref<StudentTemplateDetail | null>(null)
const students = ref<EditableStudent[]>(defaultStudents())
const loading = ref(true)
const starting = ref(false)
const errorMessage = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await getStudentTemplate(templateId.value)
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
      template_id: detail.value.template_id,
      training_level: detail.value.training_level,
      course_id: null,
      topic: null,
      virtual_students: students.value.map(toWireStudent),
      material_id: null,
    })
    await router.push(`/student/training/sessions/${session.session_id}`)
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '训练创建失败，请重试。'
  } finally {
    starting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="config-page">
    <div class="config-page__top">
      <ElButton text @click="back">← 返回训练</ElButton>
      <ElButton type="primary" :loading="starting" :disabled="!detail" @click="start">
        开始训练
      </ElButton>
    </div>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else-if="detail">
      <ElCard shadow="never" class="summary-card">
        <div class="summary-card__header">
          <div>
            <h1>{{ detail.topic }}</h1>
            <p>{{ detail.course_name }} · {{ levelLabel(detail.training_level) }}</p>
          </div>
          <ElTag effect="dark" type="warning">
            训练 · {{ levelLabel(detail.training_level) }}
          </ElTag>
        </div>
        <div class="summary-grid">
          <div><span>课程</span><strong>{{ detail.course_name }}</strong></div>
          <div><span>时长</span><strong>{{ formatDuration(detail.duration_ms) }}</strong></div>
          <div><span>档位</span><strong>{{ levelLabel(detail.training_level) }}</strong></div>
          <div><span>维度</span><strong>{{ detail.dimensions.join(' / ') }}</strong></div>
        </div>
        <p class="fixed-note">
          时长与权重由模板固定，不可修改；参考材料仅用于评测，不会在课堂中展示。
        </p>
      </ElCard>

      <ElCard shadow="never">
        <template #header>
          <h2>虚拟学生（可调整）</h2>
        </template>
        <VirtualStudentEditor v-model="students" />
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.config-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.config-page__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.state {
  color: #8fa3c0;
}

.summary-card__header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.summary-card__header h1 {
  margin: 0 0 6px;
}

.summary-card__header p {
  margin: 0;
  color: #8fa3c0;
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
  color: #8fa3c0;
  font-size: 12px;
}

.summary-grid strong {
  color: #e8eef9;
  font-size: 14px;
}

.fixed-note {
  margin: 16px 0 0;
  color: #8fa3c0;
  font-size: 12px;
}
</style>
