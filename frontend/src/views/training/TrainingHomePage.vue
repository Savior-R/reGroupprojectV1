<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElEmpty,
  ElMessage,
  ElMessageBox,
  ElRadioButton,
  ElRadioGroup,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import {
  copyTemplateFromPublication,
  deleteMyTemplate,
  listMyTemplates,
  listStudentTemplates,
} from './api'
import {
  formatDuration,
  formatSeconds,
  levelLabel,
  sourceLabel,
} from './format'
import type {
  StudentTemplateCard,
  TrainingLevel,
  TrainingTemplateSummary,
} from './types'

/** Student training entry (08 §12.2): teacher publications, my templates, free training. */

const router = useRouter()
const level = ref<TrainingLevel>('progressive')
const publications = ref<StudentTemplateCard[]>([])
const myTemplates = ref<TrainingTemplateSummary[]>([])
const loading = ref(false)
const copyingId = ref<string | null>(null)
const errorMessage = ref('')

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [published, mine] = await Promise.all([
      listStudentTemplates(level.value, null, 1, 50),
      listMyTemplates({ role: 'student', level: level.value, status: 'active', size: 50 }),
    ])
    publications.value = published.items
    myTemplates.value = mine.items
  } catch (error) {
    errorMessage.value = errorText(error, '训练模板加载失败，请重试。')
  } finally {
    loading.value = false
  }
}

function startPublication(card: StudentTemplateCard): void {
  void router.push({
    name: 'student-training-template',
    params: { publicationId: card.publication_id },
  })
}

async function copyPublication(card: StudentTemplateCard): Promise<void> {
  copyingId.value = card.publication_id
  errorMessage.value = ''
  try {
    await copyTemplateFromPublication(card.publication_id, null)
    ElMessage.success('已复制到我的模板')
    await load()
  } catch (error) {
    errorMessage.value = errorText(error, '复制模板失败，请重试。')
  } finally {
    copyingId.value = null
  }
}

function startMyTemplate(template: TrainingTemplateSummary): void {
  void router.push({
    name: 'student-training-config',
    query: { mode: 'template', templateId: template.template_id },
  })
}

function referenceMyTemplate(template: TrainingTemplateSummary): void {
  void router.push({
    name: 'student-training-config',
    query: { mode: 'free', templateId: template.template_id },
  })
}

function editMyTemplate(template: TrainingTemplateSummary): void {
  void router.push({
    name: 'student-training-template-edit',
    params: { templateId: template.template_id },
  })
}

async function removeMyTemplate(template: TrainingTemplateSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '删除后不可恢复；关联的训练记录、录像、报告与参考材料会清理；已复制的学生副本不受影响。',
      `删除「${template.name}」`,
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteMyTemplate(template.template_id)
    ElMessage.success('模板已删除')
    await load()
  } catch (error) {
    errorMessage.value = errorText(error, '删除失败，请重试。')
  }
}

function createMyTemplate(): void {
  void router.push({ name: 'student-training-template-new' })
}

function startFree(): void {
  void router.push({ name: 'student-training-config', query: { mode: 'free' } })
}

function openRecords(): void {
  void router.push('/student/training/records')
}

watch(level, () => void load())
onMounted(load)
</script>

<template>
  <div class="training-home">
    <header class="training-home__header">
      <div>
        <h1>训练</h1>
        <p>选择教师发布的模板，或用自己的模板、自由配置开始一次模拟课堂。</p>
      </div>
      <div class="training-home__header-actions">
        <ElButton @click="openRecords">训练记录</ElButton>
        <ElButton type="primary" plain @click="startFree">自由训练</ElButton>
      </div>
    </header>

    <ElRadioGroup v-model="level" class="level-switch">
      <ElRadioButton value="progressive">进阶</ElRadioButton>
      <ElRadioButton value="mock_exam">模拟考核</ElRadioButton>
    </ElRadioGroup>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
      <ElButton size="small" @click="load">重试</ElButton>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else>
      <section class="group">
        <div class="group__head">
          <h2>教师发布模板</h2>
          <span>{{ levelLabel(level) }} · 目标课程已发布的训练模板</span>
        </div>

        <ElEmpty
          v-if="publications.length === 0"
          description="当前课程暂无教师模板，可自由训练或从零创建个人模板"
        />
        <div v-else class="card-grid">
          <article v-for="card in publications" :key="card.publication_id" class="training-card">
            <div class="training-card__head">
              <h3>{{ card.name }}</h3>
              <div class="training-card__badges">
                <ElTag v-if="card.copied" type="success" effect="plain">已复制</ElTag>
                <ElTag class="training-tag" effect="dark">
                  训练 · {{ levelLabel(card.training_level) }}
                </ElTag>
              </div>
            </div>
            <p class="training-card__topic">{{ card.topic }}</p>
            <p class="training-card__meta">
              {{ card.course_name }} · {{ formatDuration(card.duration_ms) }} ·
              {{ card.virtual_student_count }} 名学生
            </p>
            <div class="training-card__actions">
              <ElButton type="primary" @click="startPublication(card)">开始训练</ElButton>
              <ElButton
                :loading="copyingId === card.publication_id"
                :disabled="card.copied"
                @click="copyPublication(card)"
              >
                {{ card.copied ? '已复制' : '复制到我的模板' }}
              </ElButton>
            </div>
          </article>
        </div>
      </section>

      <section class="group">
        <div class="group__head">
          <h2>我的模板</h2>
          <span>{{ levelLabel(level) }} · 自建或复制的个人模板，最多 30 个</span>
        </div>

        <div class="card-grid">
          <article class="training-card training-card--mine">
            <div class="create-card">
              <h3>新建模板</h3>
              <p>从零配置课题、时长、权重与虚拟学生。</p>
              <ElButton type="primary" plain @click="createMyTemplate">从零创建</ElButton>
            </div>
          </article>

          <article
            v-for="template in myTemplates"
            :key="template.template_id"
            class="training-card training-card--mine"
          >
            <div class="training-card__head">
              <h3>{{ template.name }}</h3>
              <ElTag type="info" effect="plain">{{ sourceLabel(template.source_type) }}</ElTag>
            </div>
            <p class="training-card__topic">{{ template.topic }}</p>
            <p class="training-card__meta">
              {{ levelLabel(template.training_level) }} ·
              {{ formatSeconds(template.duration_seconds) }} ·
              {{ template.virtual_student_count }} 名学生
            </p>
            <div class="training-card__actions training-card__actions--wrap">
              <ElButton type="primary" @click="startMyTemplate(template)">开始训练</ElButton>
              <ElButton @click="referenceMyTemplate(template)">引用配置</ElButton>
              <ElButton text @click="editMyTemplate(template)">编辑</ElButton>
              <ElButton text type="danger" @click="removeMyTemplate(template)">删除</ElButton>
            </div>
          </article>
        </div>
      </section>

      <section class="free-card">
        <div>
          <h2>自由训练</h2>
          <p>自选课题、可选参考材料、自定义虚拟学生；可引用我的模板带入配置。</p>
        </div>
        <ElButton type="primary" @click="startFree">开始自由训练</ElButton>
      </section>
    </template>
  </div>
</template>

<style scoped>
.training-home {
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 20px;
}

.training-home__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.training-home__header h1 {
  margin: 0 0 6px;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
}

.training-home__header p {
  margin: 0;
  color: var(--m1-text-secondary);
}

.training-home__header-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.level-switch {
  justify-self: start;
}

.state {
  color: var(--m1-text-secondary);
}

.group {
  display: grid;
  gap: 12px;
}

.group__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.group__head h2 {
  margin: 0;
  color: var(--m1-text-primary);
  font-size: 18px;
}

.group__head span {
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 14px;
}

.training-card {
  border: 1px solid var(--m1-border);
  border-radius: 14px;
  background: var(--m1-bg-surface);
  padding: 16px;
  display: grid;
  gap: 8px;
  box-shadow: var(--m1-shadow-card);
  transition: box-shadow 0.18s ease, transform 0.18s ease;
}

.training-card:hover {
  box-shadow: var(--m1-shadow-pop);
  transform: translateY(-1px);
}

.training-card--mine {
  border-style: dashed;
}

.training-card__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.training-card__head h3 {
  margin: 0;
  color: var(--m1-text-primary);
  font-size: 16px;
}

.training-card__badges {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.training-tag {
  border: 1px solid var(--m1-accent);
  background: var(--m1-accent-soft);
  color: var(--m1-accent);
}

.training-card__topic {
  margin: 0;
  color: var(--m1-text-regular);
  font-size: 13px;
}

.training-card__meta {
  margin: 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.training-card__actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.training-card__actions--wrap {
  flex-wrap: wrap;
}

.create-card {
  display: grid;
  gap: 8px;
  align-content: center;
  min-height: 150px;
}

.create-card h3 {
  margin: 0;
  color: var(--m1-text-primary);
  font-size: 16px;
}

.create-card p {
  margin: 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.create-card .el-button {
  justify-self: start;
}

.free-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid var(--m1-accent);
  border-radius: 14px;
  background: var(--m1-accent-soft);
}

.free-card h2 {
  margin: 0 0 4px;
  color: var(--m1-text-primary);
  font-size: 17px;
}

.free-card p {
  margin: 0;
  color: var(--m1-text-secondary);
  font-size: 13px;
}

@media (max-width: 720px) {
  .training-home__header,
  .free-card {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
