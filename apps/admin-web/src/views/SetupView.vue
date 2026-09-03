<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, OfficeBuilding, User } from '@element-plus/icons-vue'
import { firstAuthorizedPath } from '../config/access'
import { useAuthStore } from '../stores/auth'
import { useSetupStore } from '../stores/setup'

const router = useRouter()
const auth = useAuthStore()
const setup = useSetupStore()
const loading = ref(false)
const accepted = ref(false)
const form = reactive({
  companyName: '',
  projectName: '',
  adminDisplayName: '',
  adminUsername: '',
  adminPassword: '',
  confirmPassword: '',
})

const canSubmit = computed(() => Object.values(form).every((value) => value.trim()) && accepted.value)

async function submit() {
  if (!canSubmit.value) {
    ElMessage.warning('请完整填写初始化信息并确认数据边界')
    return
  }
  if (form.adminPassword !== form.confirmPassword) {
    ElMessage.error('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    await setup.initialize({
      companyName: form.companyName,
      projectName: form.projectName,
      adminDisplayName: form.adminDisplayName,
      adminUsername: form.adminUsername,
      adminPassword: form.adminPassword,
    })
    await auth.login(form.adminUsername.trim(), form.adminPassword)
    ElMessage.success('项目初始化完成，已进入系统')
    await router.replace(firstAuthorizedPath(auth.user?.permissions))
  } catch (error: any) {
    if (error.response?.data?.code === 'SETUP_ALREADY_COMPLETED') {
      await setup.loadStatus(true)
      ElMessage.warning('系统已经初始化，请使用现有账号登录')
      await router.replace('/login')
      return
    }
    ElMessage.error(error.response?.data?.message || error.message || '初始化失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page setup-page">
    <header class="login-header">
      <div class="login-header-brand">
        <span class="cloud-mark">物</span>
        <strong>物业管理系统</strong>
        <span class="login-product-name">首次配置</span>
      </div>
      <div class="setup-mode-badge">单项目 · 独立数据库</div>
    </header>

    <section class="login-banner" aria-label="首次配置说明">
      <div class="login-banner-copy">
        <p>FIRST-RUN SETUP</p>
        <h1>创建你的物业项目</h1>
        <span>完成一次配置后，初始化入口将永久关闭</span>
      </div>
      <div class="banner-orbit orbit-one"></div>
      <div class="banner-orbit orbit-two"></div>
    </section>

    <main class="setup-main">
      <section class="setup-card">
        <div class="login-card-title setup-card-title">
          <div>
            <h2>项目与管理员</h2>
            <span>每套部署只启用一个物业项目，后续账号在“企业管理”中维护</span>
          </div>
          <el-steps :active="1" simple finish-status="success" class="setup-steps">
            <el-step title="系统就绪" />
            <el-step title="创建项目" />
            <el-step title="进入系统" />
          </el-steps>
        </div>

        <el-form class="setup-form" label-position="top" size="large" @submit.prevent="submit">
          <div class="setup-section-title"><el-icon><OfficeBuilding /></el-icon> 项目信息</div>
          <div class="setup-grid">
            <el-form-item label="物业企业名称">
              <el-input v-model="form.companyName" maxlength="160" aria-label="物业企业名称" placeholder="例如：青岛某某物业服务有限公司" />
            </el-form-item>
            <el-form-item label="项目名称">
              <el-input v-model="form.projectName" maxlength="160" aria-label="项目名称" placeholder="例如：某某小区" />
            </el-form-item>
          </div>

          <div class="setup-section-title"><el-icon><User /></el-icon> 首个管理员</div>
          <div class="setup-grid">
            <el-form-item label="管理员姓名">
              <el-input v-model="form.adminDisplayName" maxlength="120" aria-label="管理员姓名" placeholder="用于页面显示" />
            </el-form-item>
            <el-form-item label="登录账号">
              <el-input v-model="form.adminUsername" :prefix-icon="User" maxlength="80" autocomplete="username" aria-label="登录账号" placeholder="中英文、数字、点、下划线或连字符" />
            </el-form-item>
            <el-form-item label="登录密码">
              <el-input v-model="form.adminPassword" :prefix-icon="Lock" type="password" show-password autocomplete="new-password" aria-label="登录密码" placeholder="至少 12 位且包含大小写、数字和符号" />
            </el-form-item>
            <el-form-item label="确认密码">
              <el-input v-model="form.confirmPassword" :prefix-icon="Lock" type="password" show-password autocomplete="new-password" aria-label="确认密码" placeholder="请再次输入密码" @keyup.enter="submit" />
            </el-form-item>
          </div>

          <el-alert type="info" :closable="false" show-icon class="setup-data-note">
            <template #title>数据说明</template>
            系统不会预置房屋、客户、车位、费用、账单或访客数据。首次配置完成后，请在“数据迁移”页面按模板导入并核对正式业务数据。
          </el-alert>
          <el-checkbox v-model="accepted" class="setup-accept">
            我已了解：该数据库只服务当前项目，初始化信息提交后不能再次通过此页面修改
          </el-checkbox>
          <el-button type="primary" native-type="submit" :loading="loading" :disabled="!canSubmit" class="login-submit setup-submit">
            创建项目并进入系统
          </el-button>
        </el-form>
      </section>
    </main>

    <footer class="login-footer">物业管理系统 · 单项目独立部署</footer>
  </div>
</template>
