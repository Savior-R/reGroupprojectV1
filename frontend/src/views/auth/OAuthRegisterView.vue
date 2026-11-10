<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listGrades,
  listPublicClasses,
  listPublicMajors,
  listSchools,
  registerWithWechat,
  setToken,
} from '../../api/auth'
import type { AccountType, ClassView, MajorView, SchoolView } from '../../api/auth'

/**
 * OAuth 引导注册（FR-M1-11 / 03 §3.2.3）：未绑定微信扫码后补齐账号信息，
 * 账号 + 档案 + 微信身份由后端在同一事务内创建。
 */

const route = useRoute()
const router = useRouter()

const ticket = ref(typeof route.query.ticket === 'string' ? route.query.ticket : '')
const accountType = ref<AccountType>('student')
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

watch(
  () => [form.majorId, form.grade] as const,
  async () => {
    if (!form.schoolId || !form.majorId || !form.grade) {
      classes.value = []
      return
    }
    classes.value = await listPublicClasses(form.schoolId, form.majorId, form.grade)
    form.classId = classes.value.length === 1 ? classes.value[0].id : ''
  },
)

async function submit(): Promise<void> {
  if (!ticket.value) {
    ElMessage.error('扫码凭证缺失，请重新扫码')
    return
  }
  if (!form.username.trim() || form.password.length < 8) {
    ElMessage.warning('请填写用户名与 8 位以上密码')
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
    const result = await registerWithWechat(ticket.value, {
      account_type: accountType.value,
      username: form.username.trim(),
      password: form.password,
      email: form.email.trim() || null,
      school_id: accountType.value === 'admin' ? null : form.schoolId,
      class_id: accountType.value === 'student' ? form.classId : null,
      major_ids: accountType.value === 'teacher' ? form.majorIds : [],
    })
    setToken(result.token)
    ElMessage.success('注册成功，已绑定微信')
    if (accountType.value === 'admin') {
      void router.push('/admin/setup')
    } else {
      void router.push('/settings/security')
    }
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '注册失败，请重新扫码')
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
      <h1>完善微信账号</h1>
      <p class="tip">微信尚未绑定账号，补齐信息后完成注册（同一事务创建账号与绑定）。</p>

      <el-form label-position="top">
        <el-form-item label="账号类型">
          <el-radio-group v-model="accountType">
            <el-radio-button value="student">学生</el-radio-button>
            <el-radio-button value="teacher">教师</el-radio-button>
            <el-radio-button value="admin">管理员</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="用户名（真实姓名）">
          <el-input v-model="form.username" maxlength="64" />
        </el-form-item>
        <el-form-item label="密码（8—64 位）">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirmPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="邮箱（可选）">
          <el-input v-model="form.email" />
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

        <el-button type="primary" class="submit" :loading="submitting" @click="submit">
          完成注册
        </el-button>
      </el-form>

      <div class="footer">
        或 <router-link to="/login">返回登录</router-link>
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
  margin: 0 0 8px;
  font-size: 22px;
  color: #0f2044;
}

.tip {
  color: #6b7280;
  font-size: 13px;
  margin-bottom: 16px;
}

.submit {
  width: 100%;
  margin-top: 8px;
}

.footer {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  color: #6b7280;
}

:deep(.el-select) {
  width: 100%;
}
</style>
