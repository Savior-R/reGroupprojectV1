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
  ElOption,
  ElSelect,
} from 'element-plus'
import { createCourse } from '../../api/courses'
import { listPublicMajors } from '../../api/auth'
import type { MajorView } from '../../api/auth'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'

/**
 * 创建课程抽屉（设计 03 §4.2 / §4.7）。
 *
 * 专业范围用 M1 公开接口 `GET /public/schools/{schoolId}/majors` 填充多选，
 * 只允许选本校启用中的专业，避免手工填错 UUID（POST /courses 的 major_ids 是 UUID 列表）。
 */

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void
  (event: 'created'): void
}>()

const auth = useAuthStore()

const name = ref('')
const majorIds = ref<string[]>([])
const endAt = ref<string>('')
const submitting = ref(false)
const errorMessage = ref('')

const majors = ref<MajorView[]>([])
const majorsLoading = ref(false)
const majorsError = ref('')

async function loadMajors(): Promise<void> {
  if (!auth.schoolId) {
    majorsError.value = '当前账号缺少学校信息，无法加载专业列表'
    return
  }
  majorsLoading.value = true
  majorsError.value = ''
  try {
    majors.value = await listPublicMajors(auth.schoolId)
    if (majors.value.length === 0) {
      majorsError.value = '本校还没有配置专业，请先在组织管理中新增专业'
    }
  } catch (error) {
    majorsError.value = error instanceof ApiError
      ? `专业列表加载失败：${error.message}`
      : '专业列表加载失败'
  } finally {
    majorsLoading.value = false
  }
}

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      name.value = ''
      majorIds.value = []
      endAt.value = ''
      errorMessage.value = ''
      void loadMajors()
    }
  },
)

async function submit(): Promise<void> {
  errorMessage.value = ''
  if (!name.value.trim()) {
    errorMessage.value = '请填写课程名称'
    return
  }
  if (majorIds.value.length === 0) {
    errorMessage.value = '请至少选择一个专业'
    return
  }
  submitting.value = true
  try {
    await createCourse({
      name: name.value.trim(),
      major_ids: [...majorIds.value],
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
        <ElSelect
          v-model="majorIds"
          multiple
          filterable
          :loading="majorsLoading"
          :disabled="majorsLoading || majors.length === 0"
          placeholder="选择一个或多个专业"
          style="width: 100%"
        >
          <ElOption
            v-for="major in majors"
            :key="major.id"
            :label="major.name"
            :value="major.id"
            :disabled="major.active === false"
          />
        </ElSelect>
        <p class="course-drawer__hint">
          只能选本校启用中的专业，且须全部在本人专业范围内；创建后不可修改。
        </p>
        <p v-if="majorsError" class="course-drawer__error">{{ majorsError }}</p>
      </ElFormItem>
      <ElFormItem label="课程结束日期（可选）">
        <ElDatePicker
          v-model="endAt"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss[Z]"
          placeholder="不填则按归档时间计算保留期"
          style="width: 100%"
        />
      </ElFormItem>
      <p v-if="errorMessage" class="course-drawer__error">{{ errorMessage }}</p>
      <ElButton
        type="primary"
        :loading="submitting"
        :disabled="majors.length === 0"
        @click="submit"
      >
        创建
      </ElButton>
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
  margin: 6px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
