<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  bindEmail,
  bindWechat,
  createWechatQr,
  me,
  sendBindCode,
  unbindEmail,
  unbindWechat,
  wechatStatus,
} from '../../api/auth'
import type { AccountView } from '../../api/auth'
import AdminShell from './AdminShell.vue'

/**
 * 账号安全（FR-M1-09 / FR-M1-11 / 03 §3.9 /settings/security）：
 * 绑定 / 换绑 / 解绑邮箱与微信，均需当前密码 + 重新验证。
 */

const account = ref<AccountView | null>(null)
const loading = ref(false)
const countdown = ref(0)
let timer: number | undefined

const emailForm = reactive({ email: '', code: '', password: '' })
const wechat = reactive({ ticket: '', password: '', state: '' })

async function reload(): Promise<void> {
  account.value = await me()
}

function startCountdown(): void {
  countdown.value = 60
  window.clearInterval(timer)
  timer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      window.clearInterval(timer)
    }
  }, 1000)
}

async function requestBindCode(): Promise<void> {
  if (!emailForm.email) {
    ElMessage.warning('请输入待绑定邮箱')
    return
  }
  try {
    await sendBindCode(emailForm.email.trim())
    ElMessage.success('验证码已发送')
    startCountdown()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '发送失败')
  }
}

async function submitBindEmail(): Promise<void> {
  if (!emailForm.email || !emailForm.code || !emailForm.password) {
    ElMessage.warning('请填写邮箱、验证码与当前密码')
    return
  }
  loading.value = true
  try {
    await bindEmail(emailForm.email.trim(), emailForm.code.trim(), emailForm.password)
    ElMessage.success('邮箱绑定成功')
    emailForm.email = ''
    emailForm.code = ''
    emailForm.password = ''
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '绑定失败')
  } finally {
    loading.value = false
  }
}

async function removeEmail(): Promise<void> {
  if (!emailForm.password) {
    ElMessage.warning('请输入当前密码')
    return
  }
  try {
    await unbindEmail(emailForm.password)
    ElMessage.success('邮箱已解绑')
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '解绑失败')
  }
}

async function startWechatBind(): Promise<void> {
  const qr = await createWechatQr()
  wechat.state = qr.state
  ElMessage.info('请扫码，完成后点击“我已扫码”')
}

async function confirmWechatBind(): Promise<void> {
  if (!wechat.state) {
    ElMessage.warning('请先生成二维码')
    return
  }
  try {
    const state = await wechatStatus(wechat.state)
    if (state.status === 'needs_registration' && state.ticket) {
      wechat.ticket = state.ticket
      ElMessage.success('扫码成功，请输入密码完成绑定')
    } else if (state.status === 'authorized') {
      ElMessage.warning('该微信已绑定账号，无法再次绑定')
    } else {
      ElMessage.info(`当前状态：${state.status}`)
    }
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '查询失败')
  }
}

async function submitBindWechat(): Promise<void> {
  if (!wechat.ticket || !wechat.password) {
    ElMessage.warning('请先扫码并输入当前密码')
    return
  }
  try {
    await bindWechat(wechat.ticket, wechat.password)
    ElMessage.success('微信绑定成功')
    wechat.ticket = ''
    wechat.password = ''
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '绑定失败')
  }
}

async function removeWechat(): Promise<void> {
  if (!wechat.password) {
    ElMessage.warning('请输入当前密码')
    return
  }
  try {
    await unbindWechat(wechat.password)
    ElMessage.success('微信已解绑')
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '解绑失败')
  }
}

onMounted(reload)
</script>

<template>
  <AdminShell>
    <div class="security-page">
      <div class="page-head">
        <h2>账号安全</h2>
      </div>
    <p class="tip">账号类型注册后不可变更；绑定 / 解绑均需重新验证。</p>

    <el-card v-if="account" class="card">
      <template #header>账号信息</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="账号 ID">{{ account.account_id }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ account.username }}</el-descriptions-item>
        <el-descriptions-item label="账号类型">{{ account.account_type }}</el-descriptions-item>
        <el-descriptions-item label="学校">{{ account.school_name ?? '未绑定' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="card">
      <template #header>邮箱</template>
      <p class="status">
        当前：{{ account?.email ?? '未绑定' }}
        <el-tag v-if="account?.email_verified" type="success" size="small">已验证</el-tag>
      </p>
      <el-form label-position="top">
        <el-form-item label="邮箱">
          <el-input v-model="emailForm.email" placeholder="user@example.com" />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="row">
            <el-input v-model="emailForm.code" maxlength="6" />
            <el-button :disabled="countdown > 0" @click="requestBindCode">
              {{ countdown > 0 ? `${countdown}s` : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="当前密码（绑定 / 解绑都需要）">
          <el-input v-model="emailForm.password" type="password" show-password />
        </el-form-item>
        <div class="row">
          <el-button type="primary" :loading="loading" @click="submitBindEmail">
            绑定 / 换绑
          </el-button>
          <el-button :disabled="!account?.email" @click="removeEmail">解绑邮箱</el-button>
        </div>
      </el-form>
    </el-card>

    <el-card class="card">
      <template #header>微信</template>
      <p class="status">扫码后输入当前密码完成绑定；解绑后不能再扫码登录。</p>
      <div class="row">
        <el-button @click="startWechatBind">生成二维码</el-button>
        <el-button :disabled="!wechat.state" @click="confirmWechatBind">我已扫码</el-button>
      </div>
      <el-form label-position="top" class="wechat-form">
        <el-form-item label="当前密码">
          <el-input v-model="wechat.password" type="password" show-password />
        </el-form-item>
        <div class="row">
          <el-button type="primary" :disabled="!wechat.ticket" @click="submitBindWechat">
            绑定微信
          </el-button>
          <el-button @click="removeWechat">解绑微信</el-button>
        </div>
      </el-form>
    </el-card>
    </div>
  </AdminShell>
</template>

<style scoped>
.security-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px;
}

h2 {
  margin: 0 0 4px;
  color: var(--el-text-color-primary);
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 16px;
}

.card {
  margin-bottom: 16px;
}

.status {
  color: var(--el-text-color-regular);
  font-size: 13px;
  margin: 0 0 12px;
}

.row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.wechat-form {
  margin-top: 12px;
}
</style>
