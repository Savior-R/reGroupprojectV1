<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElRadioButton,
  ElRadioGroup,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import { listStudentTemplates } from './api'
import { formatDuration, levelLabel } from './format'
import type { StudentTemplateCard, TrainingLevel } from './types'

/** Student training entry (08 §12.2): level switch, templates, free training, records link. */

const router = useRouter()
const level = ref<TrainingLevel>('progressive')
const templates = ref<StudentTemplateCard[]>([])
const loading = ref(false)
const errorMessage = ref('')

async function loadTemplates(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await listStudentTemplates(level.value, null, 1, 50)
    templates.value = page.items
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '训练模板加载失败，请重试。'
  } finally {
    loading.value = false
  }
}

function openTemplate(template: StudentTemplateCard): void {
  void router.push(`/student/training/templates/${template.template_id}`)
}

function startFree(): void {
  void router.push({ path: '/student/training/free', query: { level: level.value } })
}

function openRecords(): void {
  void router.push('/student/training/records')
}

watch(level, () => void loadTemplates())
onMounted(loadTemplates)
</script>

<template>
  <div class="training-home">
    <header class="training-home__header">
      <div>
        <h1>训练</h1>
        <p>选择教师模板，或自由配置一次模拟课堂。</p>
      </div>
      <ElButton @click="openRecords">最近训练记录</ElButton>
    </header>

    <ElRadioGroup v-model="level" class="level-switch">
      <ElRadioButton value="progressive">进阶</ElRadioButton>
      <ElRadioButton value="mock_exam">模拟考核</ElRadioButton>
    </ElRadioGroup>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
      <ElButton size="small" @click="loadTemplates">重试</ElButton>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else>
      <ElEmpty
        v-if="templates.length === 0"
        description="当前课程暂无教师模板，可先自由训练"
      />
      <section v-else class="template-grid">
        <ElCard
          v-for="template in templates"
          :key="template.template_id"
          class="template-card"
          shadow="hover"
        >
          <template #header>
            <div class="template-card__header">
              <h2>{{ template.topic }}</h2>
              <ElTag effect="dark" type="warning">{{ levelLabel(template.training_level) }}</ElTag>
            </div>
          </template>
          <p class="template-card__meta">{{ template.course_name }}</p>
          <p class="template-card__meta">
            {{ formatDuration(template.duration_ms) }} · {{ template.virtual_student_count }} 名学生
          </p>
          <ElButton type="primary" @click="openTemplate(template)">开始训练</ElButton>
        </ElCard>
      </section>

      <ElCard class="free-card" shadow="never">
        <div class="free-card__body">
          <div>
            <h2>自由训练</h2>
            <p>自选课题 / 可选参考材料 / 自定义虚拟学生</p>
          </div>
          <ElButton type="warning" @click="startFree">开始自由训练</ElButton>
        </div>
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.training-home {
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 18px;
}

.training-home__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.training-home__header h1 {
  margin: 0 0 6px;
}

.training-home__header p {
  margin: 0;
  color: #8fa3c0;
}

.level-switch {
  justify-self: start;
}

.state {
  color: #8fa3c0;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.template-card {
  border-radius: 12px;
}

.template-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.template-card__header h2 {
  margin: 0;
  font-size: 18px;
}

.template-card__meta {
  margin: 0 0 8px;
  color: #8fa3c0;
}

.free-card {
  border-radius: 12px;
  border: 1px solid rgba(245, 165, 36, 0.45);
}

.free-card__body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.free-card__body h2 {
  margin: 0 0 6px;
}

.free-card__body p {
  margin: 0;
  color: #8fa3c0;
}
</style>
