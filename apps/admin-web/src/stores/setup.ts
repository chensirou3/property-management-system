import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '../api/http'

export interface SetupStatus {
  initialized: boolean
  deploymentMode: 'SINGLE_PROJECT'
  companyName?: string
  projectName?: string
}

export interface SetupRequest {
  companyName: string
  projectName: string
  adminUsername: string
  adminDisplayName: string
  adminPassword: string
}

export const useSetupStore = defineStore('setup', () => {
  const status = ref<SetupStatus | null>(null)
  let pending: Promise<SetupStatus> | null = null

  async function loadStatus(force = false) {
    if (!force && status.value) return status.value
    if (!force && pending) return pending
    pending = (async () => {
      if (import.meta.env.VITE_USE_MOCK === 'true') {
        status.value = { initialized: true, deploymentMode: 'SINGLE_PROJECT' }
      } else {
        const response = await http.get<SetupStatus>('/setup/status')
        status.value = response.data
      }
      return status.value
    })()
    try {
      return await pending
    } finally {
      pending = null
    }
  }

  async function initialize(request: SetupRequest) {
    const { data } = await http.post('/setup/initialize', request)
    status.value = {
      initialized: true,
      deploymentMode: 'SINGLE_PROJECT',
      companyName: data.companyName,
      projectName: data.projectName,
    }
    return data
  }

  return { status, loadStatus, initialize }
})
