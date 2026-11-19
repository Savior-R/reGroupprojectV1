<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createSchool, setToken } from '../../api/auth'

/** 首次建校（FR-M1-03 / 03 §3.9 /admin/setup）：建校后服务端返回新 token，替换旧 token。 */

const router = useRouter()
const name = ref('')
const loading = ref(false)

async function submit(): Promise<void> {
  if (!name.value.trim()) {
    ElMessage.warning('请输入学校名称')
    return
  }
  loading.value = true
  try {
    const result = await createSchool(name.value.trim())
    // school_id 已写入新 token，无需重新登录（03 §3.4）
    setToken(result.token)
    ElMessage.success(`已创建学校：${result.school.name}`)
    void router.push('/admin')
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '创建失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="setup-page">
    <el-card class="card">
      <template #header>创建学校</template>
      <ol class="steps">
        <li>填写学校名称（平台内唯一）</li>
        <li>创建后自动与你的账号绑定，且不能创建第二所</li>
        <li>随后可导入专业与班级，供学生 / 教师注册选择</li>
      </ol>
      <el-form label-position="top">
        <el-form-item label="学校名称">
          <el-input v-model="name" maxlength="100" placeholder="例如：XX 大学" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="submit">创建并绑定</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.setup-page {
  display: flex;
  justify-content: center;
  padding: 48px 24px;
}

.card {
  width: 520px;
}

.steps {
  margin: 0 0 16px;
  padding-left: 20px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.9;
}
</style>
