<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createWechatQr,
  exchangeWechatTicket,
  login,
  loginWithEmail,
  sendEmailCode,
  setToken,
  wechatStatus,
} from '../../api/auth'
import type { AuthResult, QrView } from '../../api/auth'
import { useAuthStore } from '../../stores/auth'

/** 登录页（03 §3.9 /login）：密码、邮箱验证码、微信扫码三 tab，登录后按角色进入首页。 */

const router = useRouter()
const route = useRoute()

const activeTab = ref<'password' | 'email' | 'wechat'>('password')
const loading = ref(false)

const passwordForm = reactive({ username: '', password: '' })
const emailForm = reactive({ email: '', code: '' })
const countdown = ref(0)
let countdownTimer: number | undefined

const qr = ref<QrView | null>(null)
const qrStatus = ref<string>('')
let pollTimer: number | undefined

/** 开发期 Mock provider 的授权地址是本地地址，此时展示“模拟扫码”按钮。 */
const isMockProvider = computed(
  () => qr.value?.authorize_url.startsWith('http://localhost') ?? false,
)

function startCountdown(): void {
  countdown.value = 60
  window.clearInterval(countdownTimer)
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      window.clearInterval(countdownTimer)
    }
  }, 1000)
}

function finish(result: AuthResult): void {
  setToken(result.token)
  // M2/M3 的请求头身份来自这个 store，登录后必须立刻按新 token 同步一次。
  useAuthStore().syncFromToken()
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : null
  if (redirect) {
    void router.push(redirect)
    return
  }
  if (result.me.account_type === 'admin') {
    void router.push(result.me.school_id ? '/admin/org' : '/admin/setup')
  } else {
    // M2 已上线：学生 / 教师的首页就是课程列表（设计 03 §4.7）。
    void router.push('/courses')
  }
}

async function submitPassword(): Promise<void> {
  if (!passwordForm.username || !passwordForm.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    finish(await login(passwordForm.username.trim(), passwordForm.password))
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '登录失败')
  } finally {
    loading.value = false
  }
}

async function requestCode(): Promise<void> {
  if (!emailForm.email) {
    ElMessage.warning('请输入邮箱')
    return
  }
  try {
    await sendEmailCode(emailForm.email.trim())
    ElMessage.success('验证码已发送，5 分钟内有效')
    startCountdown()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '发送失败')
  }
}

async function submitEmail(): Promise<void> {
  if (!emailForm.email || !emailForm.code) {
    ElMessage.warning('请输入邮箱和验证码')
    return
  }
  loading.value = true
  try {
    finish(await loginWithEmail(emailForm.email.trim(), emailForm.code.trim()))
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '登录失败')
  } finally {
    loading.value = false
  }
}

function stopPolling(): void {
  window.clearInterval(pollTimer)
  pollTimer = undefined
}

async function loadQr(): Promise<void> {
  stopPolling()
  qrStatus.value = 'pending'
  try {
    qr.value = await createWechatQr()
    pollTimer = window.setInterval(() => void poll(), 2000)
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '二维码加载失败')
  }
}

async function poll(): Promise<void> {
  if (!qr.value) {
    return
  }
  try {
    const state = await wechatStatus(qr.value.state)
    qrStatus.value = state.status
    if (state.status === 'authorized' && state.ticket) {
      stopPolling()
      finish(await exchangeWechatTicket(state.ticket))
    } else if (state.status === 'needs_registration' && state.ticket) {
      stopPolling()
      void router.push({ path: '/register/oauth', query: { ticket: state.ticket } })
    } else if (state.status === 'expired' || state.status === 'failed') {
      stopPolling()
    }
  } catch {
    stopPolling()
  }
}

/** 开发期模拟扫码：直接触发回调，走通「未绑定 → 引导注册」与「已绑定 → 登录」两条路径。 */
async function simulateScan(bound: boolean): Promise<void> {
  if (!qr.value) {
    return
  }
  const code = bound ? 'mock-openid-dev-1' : `mock-openid-${Date.now()}`
  await fetch(
    `/api/v1/auth/oauth/wechat/callback?code=${encodeURIComponent(code)}&state=${encodeURIComponent(qr.value.state)}`,
  )
  await poll()
}

onUnmounted(() => {
  window.clearInterval(countdownTimer)
  stopPolling()
})
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <h1>试讲台</h1>
      <p class="subtitle">登录后进入{{ '' }}对应工作台</p>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="密码登录" name="password">
          <el-form label-position="top" @submit.prevent="submitPassword">
            <el-form-item label="用户名（真实姓名）">
              <el-input v-model="passwordForm.username" placeholder="请输入用户名" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="passwordForm.password"
                type="password"
                show-password
                placeholder="8—64 位"
                @keyup.enter="submitPassword"
              />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="submit" @click="submitPassword">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="邮箱验证码" name="email">
          <el-form label-position="top" @submit.prevent="submitEmail">
            <el-form-item label="已验证邮箱">
              <el-input v-model="emailForm.email" placeholder="user@example.com" />
            </el-form-item>
            <el-form-item label="验证码">
              <div class="code-row">
                <el-input v-model="emailForm.code" maxlength="6" placeholder="6 位数字" />
                <el-button :disabled="countdown > 0" @click="requestCode">
                  {{ countdown > 0 ? `${countdown}s` : '发送验证码' }}
                </el-button>
              </div>
            </el-form-item>
            <el-button type="primary" :loading="loading" class="submit" @click="submitEmail">
              登录
            </el-button>
            <p class="hint">仅已绑定并验证的邮箱可登录，未绑定的请先用密码登录后绑定。</p>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="微信扫码" name="wechat">
          <div class="qr-area">
            <el-button v-if="!qr" @click="loadQr">获取二维码</el-button>
            <template v-else>
              <p class="qr-url">{{ qr.authorize_url }}</p>
              <p class="hint">状态：{{ qrStatus }}</p>
              <div v-if="isMockProvider" class="mock-actions">
                <el-button size="small" @click="simulateScan(false)">模拟未绑定扫码</el-button>
                <el-button size="small" @click="simulateScan(true)">模拟已绑定扫码</el-button>
              </div>
              <el-button size="small" text @click="loadQr">刷新二维码</el-button>
            </template>
          </div>
        </el-tab-pane>
      </el-tabs>

      <div class="footer">
        还没有账号？<router-link to="/register">去注册</router-link>
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
}

.auth-card {
  width: 420px;
  padding: 32px;
  background: #fff;
  border-radius: 12px;
  /* 全局被 main.ts 设为 dark；卡片内恢复浅色变量，避免白底上出现深色输入框 */
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
  margin: 0 0 4px;
  font-size: 24px;
  color: #0f2044;
}

.subtitle {
  margin: 0 0 20px;
  color: #6b7280;
  font-size: 13px;
}

.submit {
  width: 100%;
}

.code-row {
  display: flex;
  gap: 8px;
}

.hint {
  margin: 8px 0 0;
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.6;
}

.qr-area {
  text-align: center;
  padding: 8px 0;
}

.qr-url {
  word-break: break-all;
  font-size: 12px;
  color: #6b7280;
}

.mock-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
  margin: 12px 0;
}

.footer {
  margin-top: 20px;
  text-align: center;
  font-size: 13px;
  color: #6b7280;
}
</style>
