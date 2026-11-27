<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import { listCourses } from '../../api/courses'
import type { CourseCardView } from '../../api/courses'
import {
  createMyTemplate,
  deleteMyTemplate,
  deleteTrainingMaterial,
  getMyTemplate,
  listPublications,
  publishTemplate,
  unpublishPublication,
  updateMyTemplate,
  uploadTemplateMaterial,
} from './api'
import {
  dimensionLabel,
  formatDateTime,
  formatSeconds,
  levelLabel,
  sourceLabel,
  unpublishReasonLabel,
} from './format'
import { defaultStudents, fromWireStudents, toWireStudent } from './students'
import VirtualStudentEditor from './components/VirtualStudentEditor.vue'
import type {
  EditableStudent,
  TrainingLevel,
  TrainingMaterialRef,
  TrainingMaterialView,
  TrainingPublicationView,
  TrainingTemplateDetail,
} from './types'

/** Personal template create / edit (08 §12.3, §12.9). Shared by the student and teacher routes. */

const route = useRoute()
const router = useRouter()

const DIMENSION_KEYS = ['content', 'expression', 'interaction', 'board', 'time'] as const

const isTeacher = computed(() => route.path.startsWith('/teacher'))
const templateId = computed(() => String(route.params.templateId ?? ''))
const isNew = computed(() => templateId.value === '')

const template = ref<TrainingTemplateDetail | null>(null)
const loading = ref(!isNew.value)
const saving = ref(false)
const uploading = ref(false)
const errorMessage = ref('')

const name = ref('')
const level = ref<TrainingLevel>('progressive')
const topic = ref('')
const textbookVersion = ref('')
const recommendedMinutes = ref(15)
const maxMinutes = ref(20)
const weights = ref<Record<string, number>>(defaultWeights())
const students = ref<EditableStudent[]>(defaultStudents())
const configLocked = ref(false)

const existingMaterial = ref<TrainingMaterialRef | null>(null)
const uploadedMaterial = ref<TrainingMaterialView | null>(null)
const removeMaterialFlag = ref(false)

const publications = ref<TrainingPublicationView[]>([])
const publishVisible = ref(false)
const publishing = ref(false)
const publishError = ref('')
const courses = ref<CourseCardView[]>([])
const selectedCourseIds = ref<string[]>([])

const ownerRole = computed(() => template.value?.owner_role ?? (isTeacher.value ? 'teacher' : 'student'))
const backTarget = computed(() =>
  isTeacher.value ? '/teacher/training/templates' : '/student/training',
)
const currentMaterialName = computed(() => {
  if (uploadedMaterial.value) {
    return uploadedMaterial.value.filename
  }
  if (existingMaterial.value && !removeMaterialFlag.value) {
    return existingMaterial.value.filename
  }
  return ''
})
const weightTotal = computed(() =>
  DIMENSION_KEYS.reduce((total, key) => total + (weights.value[key] ?? 0), 0),
)
const publishedCourses = computed(() =>
  new Set(
    publications.value
      .filter((publication) => publication.status === 'published')
      .map((publication) => publication.course_id),
  ),
)
const publishableCourses = computed(() =>
  courses.value.filter(
    (course) =>
      course.status === 'active' && !course.ended && course.can_manage
        && !publishedCourses.value.has(course.id),
  ),
)

function defaultWeights(): Record<string, number> {
  return { content: 30, expression: 20, interaction: 20, board: 15, time: 15 }
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

function fillForm(detail: TrainingTemplateDetail): void {
  template.value = detail
  name.value = detail.name
  level.value = detail.training_level
  topic.value = detail.topic
  textbookVersion.value = detail.textbook_version ?? ''
  recommendedMinutes.value = Math.round(detail.duration.recommended_seconds / 60)
  maxMinutes.value = Math.round(detail.duration.max_seconds / 60)
  weights.value = { ...defaultWeights(), ...detail.weights }
  students.value = fromWireStudents(detail.virtual_students)
  configLocked.value = detail.config_locked
  existingMaterial.value = detail.reference_material
  uploadedMaterial.value = null
  removeMaterialFlag.value = false
}

async function load(): Promise<void> {
  if (isNew.value) {
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const detail = await getMyTemplate(templateId.value)
    fillForm(detail)
    if (isTeacher.value && detail.owner_role === 'teacher') {
      await loadPublications()
    }
  } catch (error) {
    errorMessage.value = errorText(error, '模板加载失败，请重试。')
  } finally {
    loading.value = false
  }
}

async function loadPublications(): Promise<void> {
  try {
    const page = await listPublications({ templateId: templateId.value, size: 50 })
    publications.value = page.items
  } catch (error) {
    errorMessage.value = errorText(error, '发布列表加载失败。')
  }
}

function onLevelChange(value: string | number | boolean | undefined): void {
  if (value === 'mock_exam') {
    level.value = 'mock_exam'
    recommendedMinutes.value = 20
    maxMinutes.value = 20
  } else {
    level.value = 'progressive'
    if (recommendedMinutes.value > maxMinutes.value) {
      recommendedMinutes.value = maxMinutes.value
    }
  }
}

function setWeight(key: string, value: number | null | undefined): void {
  weights.value = { ...weights.value, [key]: value ?? 0 }
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
    if (uploadedMaterial.value) {
      await deleteTrainingMaterial(uploadedMaterial.value.material_id, 'template')
      uploadedMaterial.value = null
    }
    uploadedMaterial.value = await uploadTemplateMaterial(file)
    removeMaterialFlag.value = false
  } catch (error) {
    errorMessage.value = errorText(error, '材料上传失败，请重试。')
  } finally {
    uploading.value = false
  }
}

async function removeMaterial(): Promise<void> {
  if (uploadedMaterial.value) {
    try {
      await deleteTrainingMaterial(uploadedMaterial.value.material_id, 'template')
    } catch (error) {
      errorMessage.value = errorText(error, '材料删除失败，请重试。')
      return
    }
    uploadedMaterial.value = null
  }
  if (existingMaterial.value) {
    existingMaterial.value = null
    removeMaterialFlag.value = true
  }
}

function validate(): string {
  if (!name.value.trim()) {
    return '模板名称必填，且不超过 100 字。'
  }
  if (!configLocked.value) {
    if (!topic.value.trim()) {
      return '课题不能为空，且不超过 100 字。'
    }
    if (recommendedMinutes.value < 1 || maxMinutes.value > 60
      || recommendedMinutes.value > maxMinutes.value) {
      return '时长需在 1—60 分钟之间，且推荐时长不能大于最高时长。'
    }
    if (weightTotal.value !== 100) {
      return '五项权重合计必须为 100。'
    }
    const invalidStudent = students.value.find((student) => !student.name.trim())
    if (invalidStudent) {
      return '虚拟学生姓名不能为空。'
    }
    if (students.value.length < 1 || students.value.length > 30) {
      return '虚拟学生人数需在 1—30 之间。'
    }
  }
  return ''
}

async function save(): Promise<void> {
  const invalid = validate()
  if (invalid) {
    errorMessage.value = invalid
    return
  }
  saving.value = true
  errorMessage.value = ''
  try {
    if (isNew.value) {
      const created = await createMyTemplate({
        owner_role: isTeacher.value ? 'teacher' : 'student',
        name: name.value.trim(),
        training_level: level.value,
        topic: topic.value.trim(),
        textbook_version: textbookVersion.value.trim(),
        duration: {
          recommended_seconds: recommendedMinutes.value * 60,
          max_seconds: maxMinutes.value * 60,
        },
        weights: { ...weights.value },
        virtual_students: students.value.map(toWireStudent),
        material_id: uploadedMaterial.value?.material_id ?? null,
      })
      ElMessage.success('模板已创建')
      await router.replace({
        name: isTeacher.value
          ? 'teacher-training-template-detail'
          : 'student-training-template-edit',
        params: { templateId: created.template_id },
      })
      await load()
      return
    }

    const updated = await updateMyTemplate(templateId.value, configLocked.value
      ? { name: name.value.trim() }
      : {
          name: name.value.trim(),
          training_level: level.value,
          topic: topic.value.trim(),
          textbook_version: textbookVersion.value.trim(),
          duration: {
            recommended_seconds: recommendedMinutes.value * 60,
            max_seconds: maxMinutes.value * 60,
          },
          weights: { ...weights.value },
          virtual_students: students.value.map(toWireStudent),
          material_id: uploadedMaterial.value?.material_id ?? null,
          remove_material: removeMaterialFlag.value,
        })
    fillForm(updated)
    if (isTeacher.value) {
      await loadPublications()
    }
    ElMessage.success('模板已保存')
  } catch (error) {
    errorMessage.value = errorText(error, '保存失败，请重试。')
  } finally {
    saving.value = false
  }
}

async function removeTemplate(): Promise<void> {
  if (isNew.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      '已发布课程会先下架；模板关联训练记录、录像、报告与参考材料会清理；学生副本与关联任务快照不受影响；删除不可恢复。',
      `删除「${name.value}」`,
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteMyTemplate(templateId.value)
    ElMessage.success('模板已删除')
    await router.push(backTarget.value)
  } catch (error) {
    errorMessage.value = errorText(error, '删除失败，请重试。')
  }
}

async function openPublish(): Promise<void> {
  publishError.value = ''
  selectedCourseIds.value = []
  publishVisible.value = true
  if (courses.value.length === 0) {
    try {
      const page = await listCourses({ scope: 'mine', status: 'active', page: 1, size: 100 })
      courses.value = page.items
    } catch (error) {
      publishError.value = errorText(error, '课程列表加载失败。')
    }
  }
}

async function publish(): Promise<void> {
  if (selectedCourseIds.value.length === 0) {
    publishError.value = '请选择至少一个目标课程。'
    return
  }
  publishing.value = true
  publishError.value = ''
  const failed: string[] = []
  for (const courseId of selectedCourseIds.value) {
    try {
      await publishTemplate(templateId.value, courseId)
    } catch (error) {
      failed.push(errorText(error, '发布失败'))
    }
  }
  publishing.value = false
  if (failed.length > 0) {
    publishError.value = failed.join('；')
    return
  }
  ElMessage.success('模板已发布')
  publishVisible.value = false
  await load()
}

async function unpublish(publication: TrainingPublicationView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `下架后「${publication.course_name}」的学生不再看到该模板；已选中训练记录保留至到期。`,
      '下架发布',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await unpublishPublication(publication.publication_id)
    ElMessage.success('发布已下架')
    await load()
  } catch (error) {
    errorMessage.value = errorText(error, '下架失败，请重试。')
  }
}

function back(): void {
  void router.push(backTarget.value)
}

onMounted(load)
</script>

<template>
  <div class="editor-page">
    <div class="editor-page__top">
      <ElButton text @click="back">← {{ isTeacher ? '我的模板' : '训练' }}</ElButton>
      <div class="editor-page__actions">
        <ElButton
          v-if="!isNew"
          type="danger"
          text
          :disabled="saving"
          @click="removeTemplate"
        >
          删除模板
        </ElButton>
        <ElButton type="primary" :loading="saving" :disabled="loading" @click="save">
          {{ isNew ? '创建模板' : '保存模板' }}
        </ElButton>
      </div>
    </div>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <ElAlert v-if="configLocked" type="warning" :closable="false" show-icon>
      配置已锁定：该模板正在课程中发布，先下架全部发布后才能修改名称以外的配置。
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else>
      <ElCard shadow="never">
        <template #header>
          <div class="card-head">
            <h1>{{ isNew ? '新建训练模板' : name }}</h1>
            <div class="card-head__tags">
              <ElTag type="info" effect="plain">{{ isTeacher ? '教师模板' : '学生模板' }}</ElTag>
              <ElTag v-if="template" type="info" effect="plain">
                {{ sourceLabel(template.source_type) }}
              </ElTag>
            </div>
          </div>
        </template>

        <div class="form-grid">
          <label class="field field--wide">
            <span>模板名称（≤ 100 字）</span>
            <ElInput v-model="name" maxlength="100" show-word-limit placeholder="例如：荷塘月色 · 进阶" />
          </label>

          <div class="field">
            <span>档位</span>
            <ElRadioGroup
              :model-value="level"
              :disabled="configLocked"
              @change="onLevelChange"
            >
              <ElRadioButton value="progressive">进阶</ElRadioButton>
              <ElRadioButton value="mock_exam">模拟考核</ElRadioButton>
            </ElRadioGroup>
          </div>

          <label class="field">
            <span>课题（≤ 100 字）</span>
            <ElInput v-model="topic" maxlength="100" :disabled="configLocked" />
          </label>

          <label class="field">
            <span>教材版本（≤ 100 字，可选）</span>
            <ElInput v-model="textbookVersion" maxlength="100" :disabled="configLocked" />
          </label>

          <div class="field field--wide duration-row">
            <span>时长</span>
            <div class="duration-row__controls">
              <label>
                <span>推荐（分钟）</span>
                <ElInputNumber
                  v-model="recommendedMinutes"
                  :min="1"
                  :max="60"
                  :step="1"
                  :disabled="configLocked || level === 'mock_exam'"
                />
              </label>
              <label>
                <span>最高（分钟）</span>
                <ElInputNumber
                  v-model="maxMinutes"
                  :min="1"
                  :max="60"
                  :step="1"
                  :disabled="configLocked || level === 'mock_exam'"
                />
              </label>
              <ElTag v-if="level === 'mock_exam'" type="info">模拟考核固定 20 分钟</ElTag>
            </div>
          </div>

          <div class="field field--wide">
            <span>评分权重（合计需为 100）</span>
            <div class="weights">
              <label v-for="key in DIMENSION_KEYS" :key="key" class="weight">
                <span>{{ dimensionLabel(key) }}</span>
                <ElInputNumber
                  :model-value="weights[key]"
                  :min="0"
                  :max="100"
                  :step="5"
                  :disabled="configLocked"
                  @update:model-value="setWeight(key, $event)"
                />
              </label>
              <ElTag :type="weightTotal === 100 ? 'success' : 'danger'">
                合计 {{ weightTotal }}
              </ElTag>
            </div>
          </div>

          <div class="field field--wide">
            <span>参考材料（可选，仅用于评测）</span>
            <div class="material">
              <div v-if="currentMaterialName" class="material__file">
                <div>
                  <strong>{{ currentMaterialName }}</strong>
                  <p v-if="uploadedMaterial">
                    {{ uploadedMaterial.char_count }} 字 · 待保存
                  </p>
                  <p v-else-if="existingMaterial">
                    {{ existingMaterial.char_count }} 字 · {{ existingMaterial.mime }}
                  </p>
                </div>
                <ElButton size="small" type="danger" text :disabled="configLocked" @click="removeMaterial">
                  移除
                </ElButton>
              </div>
              <div v-else class="material__drop">
                <p>支持 PDF / DOCX / TXT / MD，≤ 20 MB</p>
                <label class="upload-button" :class="{ 'upload-button--disabled': configLocked }">
                  <input
                    type="file"
                    accept=".pdf,.docx,.txt,.md"
                    :disabled="uploading || configLocked"
                    @change="onFileSelected"
                  >
                  <span>{{ uploading ? '解析中…' : '选择文件' }}</span>
                </label>
              </div>
            </div>
          </div>
        </div>
      </ElCard>

      <ElCard shadow="never">
        <template #header>
          <h2>虚拟学生（1—30 人）</h2>
        </template>
        <VirtualStudentEditor v-model="students" :disabled="configLocked" />
      </ElCard>

      <ElCard v-if="isTeacher && !isNew && ownerRole === 'teacher'" shadow="never">
        <template #header>
          <div class="card-head">
            <h2>发布到课程</h2>
            <ElButton type="primary" plain @click="openPublish">发布模板</ElButton>
          </div>
        </template>

        <ElAlert type="info" :closable="false" show-icon>
          目标课程归档、你被移出课程或模板被删除时，发布会自动下架；已选中训练记录保留至到期。
        </ElAlert>

        <p v-if="publications.length === 0" class="state">尚未发布到任何课程。</p>
        <ul v-else class="publication-list">
          <li v-for="publication in publications" :key="publication.publication_id">
            <div>
              <strong>{{ publication.course_name }}</strong>
              <p>
                {{ levelLabel(publication.training_level) }} ·
                {{ formatSeconds(publication.max_duration_seconds) }} ·
                {{ publication.virtual_student_count }} 名学生 ·
                发布于 {{ formatDateTime(publication.published_at) }}
              </p>
              <p v-if="publication.status === 'unpublished'" class="publication-list__reason">
                {{ unpublishReasonLabel(publication.unpublish_reason) }} ·
                {{ formatDateTime(publication.unpublished_at) }}
              </p>
            </div>
            <div class="publication-list__actions">
              <ElTag :type="publication.status === 'published' ? 'success' : 'info'">
                {{ publication.status === 'published' ? '已发布' : '已下架' }}
              </ElTag>
              <ElButton
                v-if="publication.status === 'published'"
                size="small"
                type="danger"
                text
                @click="unpublish(publication)"
              >
                下架
              </ElButton>
            </div>
          </li>
        </ul>
      </ElCard>
    </template>

    <ElDialog v-model="publishVisible" title="发布到课程" width="560px">
      <ElAlert v-if="publishError" type="error" :closable="false" show-icon>
        <template #title>{{ publishError }}</template>
      </ElAlert>
      <ElSelect
        v-model="selectedCourseIds"
        class="publish-select"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="选择你所在老师组的课程"
      >
        <ElOption
          v-for="course in publishableCourses"
          :key="course.id"
          :label="course.name"
          :value="course.id"
        />
      </ElSelect>
      <p v-if="publishableCourses.length === 0" class="state">
        没有可发布的目标课程；已发布的课程不会重复出现。
      </p>
      <p class="dialog-hint">
        发布时使用模板当前配置快照；之后模板修改不影响已发布内容，配置锁定期间需先下架再编辑。
      </p>
      <template #footer>
        <ElButton @click="publishVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="publishing"
          :disabled="publishableCourses.length === 0"
          @click="publish"
        >
          发布
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.editor-page {
  max-width: 1040px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.editor-page__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.editor-page__actions {
  display: flex;
  gap: 8px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.card-head h1,
.card-head h2 {
  margin: 0;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
}

.card-head__tags {
  display: flex;
  gap: 6px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field {
  display: grid;
  gap: 6px;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.field--wide {
  grid-column: 1 / -1;
}

.duration-row__controls {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
}

.duration-row__controls label {
  display: grid;
  gap: 4px;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.weights {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.weight {
  display: grid;
  gap: 4px;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.material {
  border: 1px dashed var(--m1-border-strong);
  border-radius: 10px;
  padding: 14px;
}

.material__file {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.material__file p {
  margin: 6px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.material__drop {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.material__drop p {
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

.upload-button--disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.upload-button input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.publication-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: grid;
  gap: 8px;
}

.publication-list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--m1-border);
  border-radius: 10px;
}

.publication-list p {
  margin: 4px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.publication-list__reason {
  color: var(--m1-warning);
}

.publication-list__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.publish-select {
  width: 100%;
  margin-bottom: 8px;
}

.dialog-hint {
  margin: 8px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.state {
  color: var(--m1-text-secondary);
}

@media (max-width: 760px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
