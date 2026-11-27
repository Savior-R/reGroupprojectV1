<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
  ElPagination,
  ElRadioButton,
  ElRadioGroup,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import {
  createTrainingSession,
  deleteTrainingMaterial,
  getMyTemplate,
  listMyTemplates,
  uploadTrainingMaterial,
} from './api'
import { dimensionLabel, formatSeconds, levelLabel } from './format'
import { defaultStudents, fromWireStudents, toWireStudent } from './students'
import VirtualStudentEditor from './components/VirtualStudentEditor.vue'
import type {
  EditableStudent,
  TrainingLevel,
  TrainingMaterialView,
  TrainingTemplateDetail,
  TrainingTemplateSummary,
} from './types'

/**
 * Training config (08 §12.4 / §12.5).
 *
 * `?mode=template&templateId=` starts a session from one of my templates;
 * `?mode=free[&templateId=]` is free training, optionally pre-filled from a template.
 */

type ConfigMode = 'template' | 'free'

const route = useRoute()
const router = useRouter()

const mode = ref<ConfigMode>(route.query.mode === 'free' ? 'free' : 'template')
const templateId = ref(String(route.query.templateId ?? ''))
const template = ref<TrainingTemplateDetail | null>(null)
const myTemplates = ref<TrainingTemplateSummary[]>([])

const level = ref<TrainingLevel>('progressive')
const topic = ref('')
const material = ref<TrainingMaterialView | null>(null)
const students = ref<EditableStudent[]>(defaultStudents())
const referenceName = ref('')

const loading = ref(true)
const starting = ref(false)
const uploading = ref(false)
const errorMessage = ref('')

const selectorVisible = ref(false)
const selectorKeyword = ref('')
const selectorPage = ref(1)
const selectorSize = 6

const isTemplateMode = computed(() => mode.value === 'template')
const filteredTemplates = computed(() => {
  const keyword = selectorKeyword.value.trim().toLowerCase()
  if (!keyword) {
    return myTemplates.value
  }
  return myTemplates.value.filter(
    (item) =>
      item.name.toLowerCase().includes(keyword) ||
      item.topic.toLowerCase().includes(keyword),
  )
})
const pagedTemplates = computed(() => {
  const start = (selectorPage.value - 1) * selectorSize
  return filteredTemplates.value.slice(start, start + selectorSize)
})

const durationHint = computed(() =>
  level.value === 'mock_exam'
    ? '模拟考核固定 20 分钟，到点自动结束'
    : '进阶：推荐 15 分钟，最长 20 分钟',
)

const canStart = computed(() => {
  if (loading.value || starting.value || uploading.value) {
    return false
  }
  if (isTemplateMode.value) {
    return template.value !== null
  }
  return true
})

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

function syncQuery(): void {
  void router.replace({
    name: 'student-training-config',
    query: {
      mode: mode.value,
      ...(templateId.value ? { templateId: templateId.value } : {}),
      ...(mode.value === 'free' && route.query.level
        ? { level: String(route.query.level) }
        : {}),
    },
  })
}

async function loadMyTemplates(): Promise<void> {
  try {
    const page = await listMyTemplates({ role: 'student', status: 'active', size: 50 })
    myTemplates.value = page.items
  } catch (error) {
    errorMessage.value = errorText(error, '我的模板加载失败。')
  }
}

async function loadTemplate(id: string, prefill: boolean): Promise<void> {
  template.value = await getMyTemplate(id)
  if (isTemplateMode.value || prefill) {
    students.value = fromWireStudents(template.value.virtual_students)
  }
  if (!isTemplateMode.value && prefill) {
    level.value = template.value.training_level
    topic.value = template.value.topic
    referenceName.value = template.value.name
  }
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    await loadMyTemplates()
    if (templateId.value) {
      await loadTemplate(templateId.value, !isTemplateMode.value)
    } else if (isTemplateMode.value && myTemplates.value.length === 1) {
      const only = myTemplates.value[0]
      if (only) {
        templateId.value = only.template_id
        await loadTemplate(only.template_id, false)
        syncQuery()
      }
    } else if (!isTemplateMode.value) {
      const queryLevel = String(route.query.level ?? '')
      level.value = queryLevel === 'mock_exam' ? 'mock_exam' : 'progressive'
    }
  } catch (error) {
    errorMessage.value = errorText(error, '训练配置加载失败，请重试。')
  } finally {
    loading.value = false
  }
}

function onModeChange(value: string | number | boolean | undefined): void {
  switchMode(value === 'free' ? 'free' : 'template')
}

function switchMode(next: ConfigMode): void {
  if (mode.value === next) {
    return
  }
  mode.value = next
  errorMessage.value = ''
  if (next === 'template') {
    if (!templateId.value) {
      selectorVisible.value = true
    } else {
      void loadTemplate(templateId.value, false)
    }
  } else {
    if (template.value) {
      level.value = template.value.training_level
      topic.value = template.value.topic
      students.value = fromWireStudents(template.value.virtual_students)
      referenceName.value = template.value.name
    } else {
      resetFreeConfig()
    }
  }
  syncQuery()
}

function resetFreeConfig(): void {
  templateId.value = ''
  template.value = null
  referenceName.value = ''
  level.value = 'progressive'
  topic.value = ''
  students.value = defaultStudents()
}

function clearReference(): void {
  resetFreeConfig()
  syncQuery()
}

function openSelector(): void {
  selectorKeyword.value = ''
  selectorPage.value = 1
  selectorVisible.value = true
}

function chooseTemplate(item: TrainingTemplateSummary): void {
  selectorVisible.value = false
  templateId.value = item.template_id
  errorMessage.value = ''
  void loadTemplate(item.template_id, !isTemplateMode.value).then(syncQuery)
}

function changeSelectorPage(next: number): void {
  selectorPage.value = next
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
    errorMessage.value = errorText(error, '材料上传失败，请重试。')
  } finally {
    uploading.value = false
  }
}

async function removeMaterial(): Promise<void> {
  if (!material.value) {
    return
  }
  try {
    await deleteTrainingMaterial(material.value.material_id, 'free_session')
    material.value = null
  } catch (error) {
    errorMessage.value = errorText(error, '材料删除失败，请重试。')
  }
}

async function start(): Promise<void> {
  if (isTemplateMode.value && !template.value) {
    errorMessage.value = '请先选择一个我的模板。'
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
      template_id: isTemplateMode.value ? template.value?.template_id ?? null : null,
      publication_id: null,
      training_level: isTemplateMode.value
        ? template.value?.training_level ?? level.value
        : level.value,
      course_id: null,
      topic: isTemplateMode.value ? null : topic.value.trim() || null,
      virtual_students: students.value.map(toWireStudent),
      material_id: isTemplateMode.value ? null : material.value?.material_id ?? null,
    })
    await router.push(`/student/training/sessions/${session.session_id}/classroom`)
  } catch (error) {
    errorMessage.value = errorText(error, '训练创建失败，请重试。')
  } finally {
    starting.value = false
  }
}

function back(): void {
  void router.push('/student/training')
}

onMounted(load)
</script>

<template>
  <div class="config-page">
    <div class="config-page__top">
      <ElButton text @click="back">← 返回训练</ElButton>
      <ElButton type="primary" :loading="starting" :disabled="!canStart" @click="start">
        开始训练
      </ElButton>
    </div>

    <div class="mode-switch">
      <ElRadioGroup :model-value="mode" @change="onModeChange">
        <ElRadioButton value="template">使用模板</ElRadioButton>
        <ElRadioButton value="free">自由训练</ElRadioButton>
      </ElRadioGroup>
      <ElButton text @click="openSelector">
        {{ isTemplateMode ? '更换模板' : '引用模板填写配置' }}
      </ElButton>
    </div>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else>
      <ElCard v-if="isTemplateMode" shadow="never" class="summary-card">
        <template v-if="template">
          <div class="summary-card__header">
            <div>
              <h1>{{ template.name }}</h1>
              <p>{{ template.topic }}<template v-if="template.textbook_version"> · {{ template.textbook_version }}</template></p>
            </div>
            <ElTag effect="dark">训练 · {{ levelLabel(template.training_level) }}</ElTag>
          </div>
          <div class="summary-grid">
            <div><span>档位</span><strong>{{ levelLabel(template.training_level) }}</strong></div>
            <div>
              <span>时长</span>
              <strong>
                推荐 {{ formatSeconds(template.duration.recommended_seconds) }} / 最长
                {{ formatSeconds(template.duration.max_seconds) }}
              </strong>
            </div>
            <div>
              <span>权重</span>
              <strong>
                {{ Object.entries(template.weights)
                  .map(([key, value]) => `${dimensionLabel(key)} ${value}`)
                  .join(' / ') }}
              </strong>
            </div>
            <div>
              <span>参考材料</span>
              <strong>{{ template.reference_material?.filename ?? '无' }}</strong>
            </div>
          </div>
          <p class="fixed-note">
            时长与权重由模板固定，不可在本次训练中修改；参考材料仅用于评测，不在课堂中展示。
          </p>
        </template>
        <ElEmpty v-else description="还没有可用的我的模板">
          <ElButton type="primary" plain @click="openSelector">选择模板</ElButton>
        </ElEmpty>
      </ElCard>

      <ElCard v-else shadow="never">
        <template #header>
          <div class="free-card__head">
            <h1>自由训练</h1>
            <div v-if="referenceName" class="reference">
              <ElTag type="success" effect="plain">已引用：{{ referenceName }}</ElTag>
              <ElButton text size="small" @click="clearReference">清除引用</ElButton>
            </div>
          </div>
        </template>

        <div class="form-row">
          <span class="form-label">档位</span>
          <ElRadioGroup v-model="level">
            <ElRadioButton value="progressive">进阶</ElRadioButton>
            <ElRadioButton value="mock_exam">模拟考核</ElRadioButton>
          </ElRadioGroup>
          <ElTag type="info">{{ levelLabel(level) }}</ElTag>
        </div>
        <p class="hint">{{ durationHint }}</p>
        <p v-if="referenceName" class="hint">
          引用仅带入课题、档位与虚拟学生；自由训练时长使用档位默认值，模板参考材料需重新上传。
        </p>

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
        <template #header>
          <h2>{{ isTemplateMode ? '虚拟学生（默认来自模板，可调整）' : '虚拟学生（默认 3 人）' }}</h2>
        </template>
        <VirtualStudentEditor v-model="students" />
      </ElCard>
    </template>

    <ElDialog v-model="selectorVisible" title="选择我的模板" width="640px">
      <ElInput
        v-model="selectorKeyword"
        class="selector__search"
        placeholder="搜索模板名称 / 课题"
        clearable
        @input="selectorPage = 1"
      />
      <ElEmpty v-if="pagedTemplates.length === 0" description="没有匹配的模板" />
      <ul v-else class="selector__list">
        <li v-for="item in pagedTemplates" :key="item.template_id">
          <div>
            <strong>{{ item.name }}</strong>
            <p>
              {{ item.topic }} · {{ levelLabel(item.training_level) }} ·
              {{ formatSeconds(item.duration_seconds) }} · {{ item.virtual_student_count }} 人
            </p>
          </div>
          <ElButton size="small" type="primary" plain @click="chooseTemplate(item)">
            选择
          </ElButton>
        </li>
      </ul>
      <ElPagination
        v-if="filteredTemplates.length > selectorSize"
        class="selector__pagination"
        layout="prev, pager, next, total"
        :total="filteredTemplates.length"
        :page-size="selectorSize"
        :current-page="selectorPage"
        @current-change="changeSelectorPage"
      />
      <template #footer>
        <ElButton @click="selectorVisible = false">取消</ElButton>
      </template>
    </ElDialog>
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
}

.mode-switch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
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

.summary-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
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

.fixed-note,
.hint {
  margin: 16px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.free-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.free-card__head h1 {
  margin: 0;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
}

.reference {
  display: flex;
  align-items: center;
  gap: 6px;
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
  color: var(--m1-text-secondary);
  font-size: 13px;
}

.material-box {
  border: 1px dashed var(--m1-border-strong);
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
  color: var(--m1-text-secondary);
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
  color: var(--m1-text-secondary);
}

.upload-button {
  position: relative;
  overflow: hidden;
  display: inline-flex;
  padding: 8px 14px;
  border: 1px solid var(--m1-accent);
  border-radius: 8px;
  color: var(--m1-accent);
  cursor: pointer;
}

.upload-button input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.selector__search {
  margin-bottom: 12px;
}

.selector__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 8px;
}

.selector__list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--m1-border);
  border-radius: 10px;
}

.selector__list p {
  margin: 4px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.selector__pagination {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
