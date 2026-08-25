<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { firstAuthorizedPath } from '../config/access'

const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    await router.push(auth.user?.passwordChangeRequired
      ? '/change-password'
      : firstAuthorizedPath(auth.user?.permissions))
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || error.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <header class="login-header">
      <div class="login-header-brand">
        <span class="cloud-mark">物</span>
        <strong>物业云</strong>
        <span class="login-product-name">物业管理系统</span>
      </div>
      <nav class="login-nav" aria-label="产品导航">
        <span class="active">首页</span>
        <span>产品概览</span>
        <span>功能说明</span>
        <span>安全边界</span>
        <span>使用帮助</span>
      </nav>
    </header>

    <section class="login-banner" aria-label="产品简介">
      <div class="login-banner-copy">
        <p>PROPERTY MANAGEMENT CLOUD</p>
        <h1>项目、资产、收费一体化管理</h1>
        <span>以统一工作台承载物业运营的日常业务流程</span>
      </div>
      <div class="banner-orbit orbit-one"></div>
      <div class="banner-orbit orbit-two"></div>
    </section>

    <main class="login-main">
      <section class="login-card">
        <div class="login-card-title">
          <h2>账号登录</h2>
          <span>欢迎使用物业管理系统</span>
        </div>
        <el-form class="login-form" label-position="top" size="large" @submit.prevent="submit">
          <el-form-item label="账号">
            <el-input v-model="username" :prefix-icon="User" autocomplete="username" aria-label="账号" placeholder="请输入账号" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="password" :prefix-icon="Lock" type="password" show-password autocomplete="current-password" aria-label="密码" placeholder="请输入密码" @keyup.enter="submit" />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="login-submit">登录</el-button>
          <p class="login-environment-note">本地重构环境 · 数据与外部通道均按安全边界运行</p>
        </el-form>
      </section>
    </main>

    <footer class="login-footer">物业管理系统 · 独立重构版本</footer>
  </div>
</template>
