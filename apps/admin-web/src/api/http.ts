import axios from 'axios'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('pms_access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  config.headers['X-Request-Id'] = crypto.randomUUID()
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem('pms_access_token')
      if (location.pathname !== '/login') location.assign('/login')
    }
    return Promise.reject(error)
  },
)

