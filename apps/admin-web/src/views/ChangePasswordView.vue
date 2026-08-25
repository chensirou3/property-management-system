<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)

function strongPassword(value: string) {
  return value.length >= 12 && /[A-Z]/.test(value) && /[a-z]/.test(value)
    && /\d/.test(value) && /[^A-Za-z0-9]/.test(value)
}

async function submit() {
  if (!strongPassword(newPassword.value)) {
    ElMessage.warning('新密码至少 12 个字符，并同时包含大写字母、小写字母、数字和符号')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  loading.value = true
  try {
    await auth.changePassword(currentPassword.value, newPassword.value)
    ElMessage.success('密码修改成功，其他旧会话已失效')
    await router.replace('/dashboard')
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '密码修改失败')
  } finally {
    loading.value = false
  }
}

function cancel() {
  auth.logout()
  void router.replace('/login')
}
</script>

<template>
  <div class="password-change-page">
    <header class="password-change-header">
      <div class="login-header-brand">
        <span class="cloud-mark">物</span>
        <strong>物业云</strong>
        <span class="login-product-name">账号安全</span>
      </div>
    </header>
    <main class="password-change-main">
      <section class="password-change-card">
        <div class="login-card-title">
          <h2>修改临时密码</h2>
          <span>首次登录或管理员重置密码后必须完成此步骤</span>
        </div>
        <el-alert title="完成修改前，业务接口会拒绝访问。新密码会让该账号的其他旧会话立即失效。"
          type="warning" :closable="false" show-icon />
        <el-form label-position="top" size="large" class="password-change-form" @submit.prevent="submit">
          <el-form-item label="当前密码" required>
            <el-input v-model="currentPassword" :prefix-icon="Lock" type="password" show-password
              autocomplete="current-password" aria-label="当前密码" />
          </el-form-item>
          <el-form-item label="新密码" required>
            <el-input v-model="newPassword" :prefix-icon="Lock" type="password" show-password
              autocomplete="new-password" aria-label="新密码" />
          </el-form-item>
          <el-form-item label="确认新密码" required>
            <el-input v-model="confirmPassword" :prefix-icon="Lock" type="password" show-password
              autocomplete="new-password" aria-label="确认新密码" @keyup.enter="submit" />
          </el-form-item>
          <p class="password-policy-note">至少 12 个字符，且同时包含大写字母、小写字母、数字和符号；不能包含完整登录账号。</p>
          <div class="password-change-actions">
            <el-button @click="cancel">退出登录</el-button>
            <el-button type="primary" native-type="submit" :loading="loading">确认修改</el-button>
          </div>
        </el-form>
      </section>
    </main>
  </div>
</template>
