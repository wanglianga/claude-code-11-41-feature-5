import { defineStore } from 'pinia'
import { post } from '../api/client'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('scf-token') || '',
    username: localStorage.getItem('scf-username') || '',
    role: localStorage.getItem('scf-role') || ''
  }),
  getters: {
    isAdmin: (s) => s.role === 'ADMIN'
  },
  actions: {
    async login(username: string, password: string) {
      const data = await post<{ token: string; username: string; role: string }>(
        '/auth/login', { username, password })
      this.token = data.token
      this.username = data.username
      this.role = data.role
      localStorage.setItem('scf-token', data.token)
      localStorage.setItem('scf-username', data.username)
      localStorage.setItem('scf-role', data.role)
    },
    logout() {
      this.token = ''
      this.username = ''
      this.role = ''
      localStorage.removeItem('scf-token')
      localStorage.removeItem('scf-username')
      localStorage.removeItem('scf-role')
    }
  }
})
