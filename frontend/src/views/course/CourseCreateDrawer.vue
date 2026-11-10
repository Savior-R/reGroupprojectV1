<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
} from 'element-plus'
import { createCourse } from '../../api/courses'
import { ApiError } from '../../types/api'

/**
 * 创建课程抽屉（设计 03 §4.2 / §4.7）。
 *
 * 专业范围当前以 UUID 列表录入：M1 的 `GET /public/schools/{id}/majors` 尚未实现，
 * 待 M1 组织接口上线后替换为多选专业选择器，本表单字段与 POST /courses 契约保持不变。
 */

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void
  (event: 'created'): void
}>()

const name = ref('')
const majorsText = ref('')
const endAt = ref<string>('')
const submitting = ref(false)
const errorMessage = ref('')

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      name.value = ''
      majorsText.value = ''
      endAt.value = ''
      errorMessage.value = ''
    }
  },
)

function parseMajorIds(): string[] {
  return majorsText.value
    .split(/[\s,，]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

async function submit(): Promise<void> {
  errorMessage.value = ''
  const majorIds = parseMajorIds()
  if (!name.value.trim()) {
    errorMessage.value = '请填写课程名称'
    return
  }
  if (majorIds.length === 0) {
    errorMessage.value = '请至少填写一个专业 ID'
    return
  }
  submitting.value = true
  try {
    await createCourse({
      name: name.value.trim(),
      major_ids: majorIds,
      end_at: endAt.value || null,
    })
    ElMessage.success('课程已创建')
    emit('update:visible', false)
    emit('created')
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? `${error.message}（${error.code}）`
      : '创建课程失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <ElDrawer
    :model-value="visible"
    title="创建课程"
    size="420px"
    @update:model-value="emit('update:visible', $event)"
  >
    <ElForm label-position="top">
      <ElFormItem label="课程名称" required>
        <ElInput v-model="name" maxlength="150" show-word-limit placeholder="如：2026 级语文教学论" />
      </ElFormItem>
      <ElFormItem label="专业范围" required>
        <ElInput
          v-model="majorsText"
          type="textarea"
          :rows="3"
          placeholder="填写专业 ID（UUID），多个用逗号或换行分隔"
        />
        <p class="course-drawer__hint">
          所选专业须全部在本人专业范围内；创建后不可修改。M1 专业选择器上线前暂以 ID 录入。
        </p>
      </ElFormItem>
      <ElFormItem label="课程结束日期（可选）">
        <ElDatePicker
          v-model="endAt"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss[Z]"
          placeholder="不填则按归档时间计算保留期"
        />
      </ElFormItem>
      <p v-if="errorMessage" class="course-drawer__error">{{ errorMessage }}</p>
      <ElButton type="primary" :loading="submitting" @click="submit">创建</ElButton>
    </ElForm>
  </ElDrawer>
</template>

<style scoped>
.course-drawer__hint {
  margin: 6px 0 0;
  font-size: 12px;
  opacity: 0.65;
}

.course-drawer__error {
  margin: 0 0 12px;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
