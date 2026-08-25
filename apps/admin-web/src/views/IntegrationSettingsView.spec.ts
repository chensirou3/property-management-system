import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'
import IntegrationSettingsView from './IntegrationSettingsView.vue'

function projectPinia() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.projects = [{ id: 'project-1', name: '合成项目', status: 'ACTIVE' }]
  auth.currentProjectId = 'project-1'
  auth.user = { id: 'user-1', username: 'tester', displayName: '测试员', roles: ['PLATFORM_ADMIN'],
    permissions: ['integration:read', 'integration:write'], projectIds: ['project-1'], passwordChangeRequired: false }
  return pinia
}

const stubs = {
  'el-alert': { template: '<div><slot name="title" /><slot /></div>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-button': { inheritAttrs: false, emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-select': { template: '<select><slot /></select>' }, 'el-option': true,
  'el-table': { template: '<div><slot /></div>' }, 'el-table-column': true,
  'el-tag': { template: '<span><slot /></span>' }, 'el-icon': { template: '<i><slot /></i>' },
  'el-tabs': { template: '<div><slot /></div>' }, 'el-tab-pane': { template: '<section><slot /></section>' },
}

const workbench = {
  adapters: [
    { adapterCode: 'PAYMENT_SIMULATOR', providerType: 'PAYMENT', providerName: '本地支付模拟器', mode: 'SIMULATOR', enabled: true, productionReady: false, credentialStatus: 'NOT_REQUIRED', signingRequired: true, timeoutMs: 3000, maxAttempts: 3, retryBaseSeconds: 5 },
    { adapterCode: 'INVOICE_SIMULATOR', providerType: 'INVOICE', providerName: '本地发票模拟器', mode: 'SIMULATOR', enabled: true, productionReady: false, credentialStatus: 'NOT_REQUIRED', signingRequired: true, timeoutMs: 3000, maxAttempts: 3, retryBaseSeconds: 5 },
    { adapterCode: 'BANK_TRUST_SIMULATOR', providerType: 'BANK', providerName: '本地银行信托模拟器', mode: 'SIMULATOR', enabled: true, productionReady: false, credentialStatus: 'NOT_REQUIRED', signingRequired: true, timeoutMs: 3000, maxAttempts: 3, retryBaseSeconds: 5 },
    { adapterCode: 'IOT_SIMULATOR', providerType: 'IOT', providerName: '本地 IoT 模拟器', mode: 'SIMULATOR', enabled: true, productionReady: false, credentialStatus: 'NOT_REQUIRED', signingRequired: true, timeoutMs: 3000, maxAttempts: 3, retryBaseSeconds: 5 },
    { adapterCode: 'JAVA110_DISABLED', providerType: 'JAVA110', providerName: 'Java110', mode: 'DISABLED', enabled: false, productionReady: false, credentialStatus: 'NOT_CONFIGURED', signingRequired: false, timeoutMs: 3000, maxAttempts: 3, retryBaseSeconds: 5 },
  ],
  outboxSummary: [{ status: 'PUBLISHED', itemCount: 6 }], callbacks: [], attempts: [], deadLetters: [],
  security: { signedCallbacks: true, maxSkewSeconds: 300, secretConfigured: true, secretsReadable: false },
  observability: { health: '/actuator/health', readiness: '/actuator/health/readiness', metrics: '/actuator/prometheus', requestTraceHeader: 'X-Request-Id' },
}

afterEach(() => vi.restoreAllMocks())

describe('G9 integration governance workbench', () => {
  it('shows five fail-closed adapters and never presents them as production channels', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: workbench } as any)
    const wrapper = mount(IntegrationSettingsView, { global: {
      plugins: [projectPinia()], directives: { loading: () => undefined }, stubs,
    } })
    await flushPromises()

    expect(wrapper.text()).toContain('当前没有任何生产通道')
    expect(wrapper.text()).toContain('支付、发票、银行和 IoT 均为本地模拟适配器')
    expect(wrapper.text()).toContain('第三方集成治理台')
    expect(wrapper.text()).toContain('密钥仅写入运行时，管理端永不回显')
    expect(get).toHaveBeenCalledWith('/integrations/workbench', { params: { communityId: 'project-1' } })
  })

  it('runs a local adapter contract test through the governed endpoint', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: workbench } as any)
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: {
      adapterCode: 'PAYMENT_SIMULATOR', mode: 'SIMULATOR', outcome: 'SUCCEEDED', productionReady: false,
    } } as any)
    const wrapper = mount(IntegrationSettingsView, { global: {
      plugins: [projectPinia()], directives: { loading: () => undefined }, stubs,
    } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '检测全部')!.trigger('click')
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/integrations/adapters/PAYMENT_SIMULATOR:test', null, {
      params: { communityId: 'project-1' },
    })
    expect(post).toHaveBeenCalledTimes(5)
  })
})
