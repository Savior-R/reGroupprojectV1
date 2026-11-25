<script setup lang="ts">
import { useRouter } from 'vue-router'
import { getToken } from '../../api/auth'
import { decodeJwt, homeFor } from '../../api/jwt'

const router = useRouter()

/** 按角色回到对应工作台，未登录则去登录页。 */
function goHome(): void {
  const claims = decodeJwt(getToken())
  void router.push(claims ? homeFor(claims) : '/login')
}

function switchAccount(): void {
  void router.push('/login')
}
</script>

<template>
  <div class="error-page">
    <div class="error-card">
      <div class="error-card__top">
        <span class="error-code">403</span>
        <span class="error-stamp">无<br />权</span>
      </div>
      <div class="error-card__body">
        <h2>你没有权限进入这间教室。</h2>
        <p>
          当前账号没有访问该页面的权限。如果这是课程页面，请联系任课教师或管理员确认你的课程 / 角色；
          如果你刚切换账号，请重新登录。
        </p>
        <div class="error-card__actions">
          <el-button type="primary" @click="goHome">回到我的工作台</el-button>
          <el-button plain @click="switchAccount">切换账号</el-button>
        </div>
      </div>
      <div class="error-card__foot">
        <span>HTTP 403 · FORBIDDEN</span>
        <span>不泄露资源是否存在</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 70vh;
  padding: 32px 20px;
  background: var(--m1-bg-page);
}

.error-card {
  position: relative;
  display: flex;
  min-height: 340px;
  width: 100%;
  max-width: 640px;
  flex-direction: column;
  justify-content: space-between;
  overflow: hidden;
  padding: 28px 32px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 28px rgba(24, 38, 34, 0.05);
}

.error-card::after {
  position: absolute;
  right: -60px;
  bottom: -80px;
  width: 240px;
  height: 240px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 50%;
  content: '';
  pointer-events: none;
}

.error-card__top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.error-code {
  color: #b4402f; /* 朱砂 */
  font-family: var(--m1-font-serif, 'Songti SC', 'SimSun', serif);
  font-size: clamp(68px, 8vw, 104px);
  font-weight: 600;
  line-height: 0.9;
  letter-spacing: -0.04em;
}

.error-stamp {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: 1.5px solid #b4402f;
  border-radius: 3px;
  color: #b4402f;
  font-family: var(--m1-font-serif, 'Songti SC', 'SimSun', serif);
  font-size: 10px;
  font-weight: 700;
  line-height: 1.2;
  text-align: center;
  transform: rotate(6deg);
  opacity: 0.72;
}

.error-card__body {
  position: relative;
  z-index: 1;
  margin-top: 22px;
}

.error-card h2 {
  margin: 0 0 10px;
  font-family: var(--m1-font-serif, 'Songti SC', 'SimSun', serif);
  font-size: 24px;
  color: var(--el-text-color-primary);
}

.error-card p {
  max-width: 36em;
  margin: 0 0 20px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.75;
}

.error-card__actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.error-card__foot {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: space-between;
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  letter-spacing: 0.04em;
}
</style>
