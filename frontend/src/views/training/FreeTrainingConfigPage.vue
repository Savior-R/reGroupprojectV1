<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElInput,
  ElRadioButton,
  ElRadioGroup,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import {
  createTrainingSession,
  deleteTrainingMaterial,
  uploadTrainingMaterial,
} from './api'
import { levelLabel } from './format'
import { defaultStudents, toWireStudent } from './students'
import VirtualStudentEditor from './components/VirtualStudentEditor.vue'
import type { EditableStudent, TrainingLevel, TrainingMaterialView } from './types'

/** Free training configuration (08 §12.4): topic, optional material, virtual students. */

const route = useRoute()
const router = useRouter()

const queryLevel = String(route.query.level ?? '')
const level = ref<TrainingLevel>(queryLevel === 'mock_exam' ? 'mock_exam' : 'progressive')
const topic = ref('')
const material = ref<TrainingMaterialView | null>(null)
const students = ref<EditableStudent[]>(defaultStudents())
const uploading = ref(false)
const starting = ref(false)
const errorMessage = ref('')

const durationText = computed(() =>
  level.value === 'mock_exam'
    ? '模拟考核固定 20 分钟，到点自动结束'
    : '进阶：推荐 15 分钟，最长 20 分钟',
)

function back(): void {
  void router.push('/student/training')
}

async function onFileSelected(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  uploading.value = true
  errorMessage.value = ''
  try {
    material.value = await uploadTrainingMaterial(file)
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '材料上传失败，请重试。'
  } finally {
    uploading.value = false
  }
}

async function removeMaterial(): Promise<void> {
  if (!material.value) {
    return
  }
  try {
    await deleteTrainingMaterial(material.value.material_id)
    material.value = null
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '材料删除失败，请重试。'
  }
}

async function start(): Promise<void> {
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
      training_level: level.value,
      course_id: null,
      topic: topic.value.trim() || null,
      virtual_students: students.value.map(toWireStudent),
      material_id: material.value?.material_id ?? null,
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
</script>

<template>
  <div class="free-page">
    <div class="free-page__top">
      <ElButton text @click="back">← 返回训练</ElButton>
      <ElButton type="primary" :loading="starting" @click="start">开始训练</ElButton>
    </div>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <ElCard shadow="never">
      <template #header><h1>自由训练</h1></template>

      <div class="form-row">
        <span class="form-label">档位</span>
        <ElRadioGroup v-model="level">
          <ElRadioButton value="progressive">进阶</ElRadioButton>
          <ElRadioButton value="mock_exam">模拟考核</ElRadioButton>
        </ElRadioGroup>
        <ElTag type="info">{{ levelLabel(level) }}</ElTag>
      </div>
      <p class="hint">{{ durationText }}</p>

      <div class="form-row form-row--column">
        <span class="form-label">课题（可选，≤ 100 字）</span>
        <ElInput v-model="topic" maxlength="100" show-word-limit placeholder="默认为「自由训练」" />
      </div>

      <div class="form-row form-row--column">
        <span class="form-label">参考材料（可选）</span>
        <div class="material-box">
          <div v-if="material" class="material-box__file">
            <div>
              <strong>{{ material.filename }}</strong>
              <p>{{ material.char_count }} 字 · 仅用于评测，教师 / 管理员不可见</p>
              <p class="material-box__preview">{{ material.preview }}</p>
            </div>
            <ElButton size="small" type="danger" text @click="removeMaterial">移除</ElButton>
          </div>
          <div v-else class="material-box__drop">
            <p>支持 PDF / DOCX / TXT / MD，≤ 20 MB</p>
            <label class="upload-button">
              <input
                type="file"
                accept=".pdf,.docx,.txt,.md"
                :disabled="uploading"
                @change="onFileSelected"
              >
              <span>{{ uploading ? '解析中…' : '选择文件' }}</span>
            </label>
          </div>
        </div>
      </div>
    </ElCard>

    <ElCard shadow="never">
      <template #header><h2>虚拟学生（默认 3 人）</h2></template>
      <VirtualStudentEditor v-model="students" />
    </ElCard>
  </div>
</template>

<style scoped>
.free-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.free-page__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.form-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.form-row--column {
  align-items: stretch;
  flex-direction: column;
}

.form-label {
  color: #8fa3c0;
  font-size: 13px;
}

.hint {
  margin: 0 0 16px;
  color: #8fa3c0;
  font-size: 12px;
}

.material-box {
  border: 1px dashed rgba(143, 163, 192, 0.4);
  border-radius: 10px;
  padding: 14px;
}

.material-box__file {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.material-box__file p {
  margin: 6px 0 0;
  color: #8fa3c0;
  font-size: 12px;
}

.material-box__preview {
  max-height: 60px;
  overflow: hidden;
}

.material-box__drop {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.material-box__drop p {
  margin: 0;
  color: #8fa3c0;
}

.upload-button {
  position: relative;
  overflow: hidden;
  display: inline-flex;
  padding: 8px 14px;
  border: 1px solid #f5a524;
  border-radius: 8px;
  color: #f5a524;
  cursor: pointer;
}

.upload-button input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
</style>
