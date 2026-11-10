<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listGrades,
  listPublicClasses,
  listPublicMajors,
  listSchools,
  register,
  setToken,
} from '../../api/auth'
import type { AccountType, ClassView, MajorView, SchoolView } from '../../api/auth'

/**
 * 两步注册（03 §3.1 / 03 §3.9 /register）：
 * 先选并确认账号类型（注册后不可变更），再按类型填学校 / 专业 / 班级。
 */

const router = useRouter()

const step = ref(1)
const accountType = ref<AccountType | null>(null)
const submitting = ref(false)

const schools = ref<SchoolView[]>([])
const majors = ref<MajorView[]>([])
const grades = ref<string[]>([])
const classes = ref<ClassView[]>([])

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
  schoolId: '',
  majorId: '',
  grade: '',
  classId: '',
  majorIds: [] as string[],
})

const typeLabel = computed(() => {
  switch (accountType.value) {
    case 'student':
      return '学生'
    case 'teacher':
      return '教师'
    case 'admin':
      return '管理员'
    default:
      return ''
  }
})

function chooseType(type: AccountType): void {
  accountType.value = type
}

function nextStep(): void {
  if (!accountType.value) {
    ElMessage.warning('请先选择账号类型')
    return
  }
  step.value = 2
}

watch(
  () => form.schoolId,
  async (schoolId) => {
    form.majorId = ''
    form.grade = ''
    form.classId = ''
    majors.value = []
    grades.value = []
    classes.value = []
    if (!schoolId) {
      return
    }
    ;[majors.value, grades.value] = await Promise.all([
      listPublicMajors(schoolId),
      listGrades(schoolId),
    ])
  },
)

async function loadClasses(): Promise<void> {
  if (!form.schoolId || !form.majorId || !form.grade) {
    classes.value = []
    return
  }
  classes.value = await listPublicClasses(form.schoolId, form.majorId, form.grade)
  // 恰好一个班时自动选中；多个由学生自选；没有则不能提交（FR-M1-05）
  form.classId = classes.value.length === 1 ? classes.value[0].id : ''
}

watch(() => [form.majorId, form.grade], loadClasses)

async function submit(): Promise<void> {
  if (!accountType.value) {
    return
  }
  if (!form.username.trim()) {
    ElMessage.warning('请输入用户名（真实姓名）')
    return
  }
  if (form.password.length < 8 || form.password.length > 64) {
    ElMessage.warning('密码需为 8—64 位')
    return
  }
  if (form.password !== form.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  if (accountType.value !== 'admin' && !form.schoolId) {
    ElMessage.warning('请选择学校')
    return
  }
  if (accountType.value === 'student' && !form.classId) {
    ElMessage.warning('请选择班级')
    return
  }
  if (accountType.value === 'teacher' && form.majorIds.length === 0) {
    ElMessage.warning('请至少选择一个专业')
    return
  }

  submitting.value = true
  try {
    const result = await register({
      account_type: accountType.value,
      username: form.username.trim(),
      password: form.password,
      email: form.email.trim() || null,
      school_id: accountType.value === 'admin' ? null : form.schoolId,
      class_id: accountType.value === 'student' ? form.classId : null,
      major_ids: accountType.value === 'teacher' ? form.majorIds : [],
    })
    setToken(result.token)
    ElMessage.success('注册成功')
    if (accountType.value === 'admin') {
      void router.push('/admin/setup')
    } else {
      void router.push('/settings/security')
    }
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '注册失败')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  schools.value = await listSchools()
})
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <h1>注册</h1>
      <el-steps :active="step" simple class="steps">
        <el-step title="选择身份" />
        <el-step title="填写信息" />
      </el-steps>

      <template v-if="step === 1">
        <p class="tip">账号类型注册后不可变更，请确认后继续。</p>
        <div class="type-grid">
          <button
            v-for="item in [
              { key: 'student', title: '学生', desc: '归属学校、年级、专业与班级' },
              { key: 'teacher', title: '教师', desc: '归属学校与一个或多个专业' },
              { key: 'admin', title: '管理员', desc: '注册后创建并管理一所学校' },
            ]"
            :key="item.key"
            type="button"
            class="type-card"
            :class="{ selected: accountType === item.key }"
            @click="chooseType(item.key as AccountType)"
          >
            <strong>{{ item.title }}</strong>
            <span>{{ item.desc }}</span>
          </button>
        </div>
        <el-button type="primary" class="submit" @click="nextStep">下一步</el-button>
      </template>

      <template v-else>
        <p class="tip">当前身份：{{ typeLabel }}（不可修改）</p>
        <el-form label-position="top">
          <el-form-item label="用户名（真实姓名，重名请加后缀）">
            <el-input v-model="form.username" maxlength="64" />
          </el-form-item>
          <el-form-item label="密码（8—64 位）">
            <el-input v-model="form.password" type="password" show-password />
          </el-form-item>
          <el-form-item label="确认密码">
            <el-input v-model="form.confirmPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="邮箱（可选，绑定后可用验证码登录）">
            <el-input v-model="form.email" placeholder="user@example.com" />
          </el-form-item>

          <el-form-item v-if="accountType !== 'admin'" label="学校">
            <el-select v-model="form.schoolId" placeholder="请选择学校" filterable>
              <el-option
                v-for="school in schools"
                :key="school.school_id"
                :label="school.name"
                :value="school.school_id"
              />
            </el-select>
          </el-form-item>

          <template v-if="accountType === 'student'">
            <el-form-item label="年级">
              <el-select v-model="form.grade" placeholder="请选择年级">
                <el-option v-for="grade in grades" :key="grade" :label="grade" :value="grade" />
              </el-select>
            </el-form-item>
            <el-form-item label="专业">
              <el-select v-model="form.majorId" placeholder="请选择专业">
                <el-option
                  v-for="major in majors"
                  :key="major.id"
                  :label="major.name"
                  :value="major.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="班级">
              <el-select v-model="form.classId" placeholder="请选择班级">
                <el-option
                  v-for="item in classes"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
              <p v-if="form.majorId && form.grade && classes.length === 0" class="hint">
                该专业年级暂无班级，请联系学校管理员。
              </p>
            </el-form-item>
          </template>

          <el-form-item v-if="accountType === 'teacher'" label="专业（可多选）">
            <el-select v-model="form.majorIds" multiple placeholder="请选择专业">
              <el-option
                v-for="major in majors"
                :key="major.id"
                :label="major.name"
                :value="major.id"
              />
            </el-select>
          </el-form-item>

          <div class="actions">
            <el-button @click="step = 1">上一步</el-button>
            <el-button type="primary" :loading="submitting" @click="submit">完成注册</el-button>
          </div>
        </el-form>
      </template>

      <div class="footer">
        已有账号？<router-link to="/login">去登录</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #0f2044;
  padding: 24px 0;
}

.auth-card {
  width: 520px;
  padding: 32px;
  background: #fff;
  border-radius: 12px;
  /* 全局被 main.ts 设为 dark；卡片内恢复浅色变量 */
  --el-bg-color: #fff;
  --el-bg-color-overlay: #fff;
  --el-text-color-primary: #0f2044;
  --el-text-color-regular: #374151;
  --el-text-color-placeholder: #9ca3af;
  --el-border-color: #d1d5db;
  --el-border-color-light: #e5e7eb;
  --el-fill-color-blank: #fff;
  --el-fill-color-light: #f3f4f6;
}

h1 {
  margin: 0 0 16px;
  font-size: 24px;
  color: #0f2044;
}

.steps {
  margin-bottom: 20px;
}

.tip {
  color: #6b7280;
  font-size: 13px;
}

.type-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin: 16px 0 24px;
}

.type-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px 12px;
  text-align: left;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  cursor: pointer;
}

.type-card.selected {
  border-color: #d97706;
  background: #fffbeb;
}

.type-card strong {
  color: #0f2044;
}

.type-card span {
  color: #6b7280;
  font-size: 12px;
  line-height: 1.5;
}

.submit {
  width: 100%;
}

.actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 8px;
}

.hint {
  margin: 6px 0 0;
  color: #b45309;
  font-size: 12px;
}

.footer {
  margin-top: 20px;
  text-align: center;
  font-size: 13px;
  color: #6b7280;
}

:deep(.el-form-item) {
  margin-bottom: 16px;
}

:deep(.el-select) {
  width: 100%;
}
</style>
