<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    await router.push('/dashboard')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-visual">
      <div class="login-brand"><span>物</span> 物业管理平台</div>
      <div class="login-message">
        <p class="eyebrow">PROPERTY MANAGEMENT</p>
        <h1>让项目、资产与收费<br />保持清晰一致</h1>
        <p>独立实现的物业运营工作台，覆盖基础档案、费用配置、收银账务与抄表流程。</p>
        <div class="login-facts">
          <div><strong>359</strong><span>合成房屋</span></div>
          <div><strong>250</strong><span>合成车位</span></div>
          <div><strong>22</strong><span>费用定义</span></div>
        </div>
      </div>
      <div class="security-note">仅使用脱敏合成数据 · 外部通道默认模拟</div>
    </section>
    <section class="login-panel">
      <div class="login-card">
        <p class="eyebrow dark">WELCOME BACK</p>
        <h2>登录管理平台</h2>
        <p class="muted">请输入本地环境管理员账号</p>
        <el-form label-position="top" size="large" @submit.prevent="submit">
          <el-form-item label="账号">
            <el-input v-model="username" :prefix-icon="User" autocomplete="username" placeholder="请输入账号" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="password" :prefix-icon="Lock" type="password" show-password autocomplete="current-password" placeholder="请输入密码" @keyup.enter="submit" />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="login-submit">登录</el-button>
        </el-form>
        <el-alert type="info" :closable="false" show-icon title="开发环境账号由后端种子脚本生成，不使用目标站凭据。" />
      </div>
    </section>
  </div>
</template>

