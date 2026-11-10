<script setup lang="ts">
import { ElButton, ElInput, ElOption, ElSelect, ElTag } from 'element-plus'
import { newStudent, randomizeStudents } from '../students'
import type { EditableStudent } from '../types'

/** Editable virtual-student group shared by template and free-training config (08 §5.2). */

const model = defineModel<EditableStudent[]>({ required: true })

const AVATARS = ['avatar-01', 'avatar-02', 'avatar-03', 'avatar-04', 'avatar-05', 'avatar-06']

type RatioKey =
  | 'activity'
  | 'question_rate'
  | 'ability'
  | 'attention_baseline'
  | 'discipline_probability'

const RATIOS: Array<{ key: RatioKey; label: string }> = [
  { key: 'activity', label: '活跃度' },
  { key: 'question_rate', label: '提问率' },
  { key: 'ability', label: '能力' },
  { key: 'attention_baseline', label: '注意力' },
  { key: 'discipline_probability', label: '纪律' },
]

function addStudent(): void {
  if (model.value.length >= 30) {
    return
  }
  model.value = [...model.value, newStudent(model.value.length + 1)]
}

function removeStudent(index: number): void {
  if (model.value.length <= 1) {
    return
  }
  model.value = model.value
    .filter((_, position) => position !== index)
    .map((student, position) => ({
      ...student,
      seat_no: position + 1,
      sort_order: position + 1,
    }))
}

function randomize(): void {
  model.value = randomizeStudents(model.value)
}

function ratioValue(student: EditableStudent, key: RatioKey): number {
  return student[key]
}

function setRatio(student: EditableStudent, key: RatioKey, value: number): void {
  student[key] = value
}
</script>

<template>
  <div class="student-editor">
    <div class="student-editor__toolbar">
      <ElTag type="info">虚拟学生 {{ model.length }} / 30</ElTag>
      <div class="student-editor__actions">
        <ElButton size="small" @click="randomize">随机姓名 / 头像</ElButton>
        <ElButton size="small" type="primary" plain :disabled="model.length >= 30" @click="addStudent">
          添加学生
        </ElButton>
      </div>
    </div>

    <div
      v-for="(student, index) in model"
      :key="student.key"
      class="student-card"
    >
      <div class="student-card__header">
        <strong>学生 {{ index + 1 }}</strong>
        <ElButton
          size="small"
          text
          type="danger"
          :disabled="model.length <= 1"
          @click="removeStudent(index)"
        >
          删除
        </ElButton>
      </div>

      <div class="student-card__basics">
        <label class="field">
          <span>姓名</span>
          <ElInput v-model="student.name" maxlength="20" />
        </label>
        <label class="field">
          <span>头像</span>
          <ElSelect v-model="student.avatar" class="field__control">
            <ElOption v-for="avatar in AVATARS" :key="avatar" :label="avatar" :value="avatar" />
          </ElSelect>
        </label>
      </div>

      <div class="student-card__ratios">
        <div v-for="ratio in RATIOS" :key="ratio.key" class="ratio">
          <span class="ratio__label">{{ ratio.label }}</span>
          <input
            class="ratio__slider"
            type="range"
            min="0"
            max="1"
            step="0.05"
            :value="ratioValue(student, ratio.key)"
            @input="setRatio(student, ratio.key, Number(($event.target as HTMLInputElement).value))"
          >
          <span class="ratio__value">{{ Math.round(ratioValue(student, ratio.key) * 100) }}%</span>
        </div>
      </div>

      <label class="field">
        <span>个性文本（≤ 500 字）</span>
        <ElInput
          v-model="student.personality_text"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="例如：性格外向，喜欢回答问题，但容易走神"
        />
      </label>
    </div>
  </div>
</template>

<style scoped>
.student-editor {
  display: grid;
  gap: 12px;
}

.student-editor__toolbar,
.student-card__header,
.student-editor__actions,
.student-card__basics {
  display: flex;
  align-items: center;
  gap: 10px;
}

.student-editor__toolbar,
.student-card__header {
  justify-content: space-between;
}

.student-editor__actions {
  margin-left: auto;
}

.student-card {
  border: 1px solid rgba(143, 163, 192, 0.22);
  border-radius: 10px;
  padding: 12px;
  display: grid;
  gap: 10px;
  background: rgba(17, 28, 51, 0.72);
}

.student-card__basics {
  flex-wrap: wrap;
}

.field {
  display: grid;
  gap: 4px;
  flex: 1;
  min-width: 160px;
  color: #8fa3c0;
  font-size: 12px;
}

.field__control {
  width: 100%;
}

.student-card__ratios {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 8px 14px;
}

.ratio {
  display: grid;
  grid-template-columns: 54px 1fr 42px;
  align-items: center;
  gap: 6px;
  color: #8fa3c0;
  font-size: 12px;
}

.ratio__slider {
  width: 100%;
  accent-color: #f5a524;
}

.ratio__value {
  text-align: right;
  color: #e8eef9;
}
</style>
