import http from './index'

export const authApi = {
  login: (data) => http.post('/auth/login', data),
  me: () => http.get('/auth/me')
}

export const jdApi = {
  page: (params) => http.get('/jd', { params }),
  get: (id) => http.get(`/jd/${id}`),
  create: (data) => http.post('/jd', data),
  update: (id, data) => http.put(`/jd/${id}`, data),
  remove: (id) => http.delete(`/jd/${id}`)
}

export const accountApi = {
  page: (params) => http.get('/account', { params }),
  create: (data) => http.post('/account', data),
  update: (id, data) => http.put(`/account/${id}`, data),
  remove: (id) => http.delete(`/account/${id}`),
  login: (id) => http.post(`/account/${id}/login`),
  loginStatus: (id) => http.get(`/account/${id}/login-status`)
}

export const userApi = {
  page: (params) => http.get('/user', { params }),
  create: (data) => http.post('/user', data),
  update: (id, data) => http.put(`/user/${id}`, data),
  remove: (id) => http.delete(`/user/${id}`)
}

export const auditApi = {
  logs: (params) => http.get('/audit/logs', { params })
}

export const candidateApi = {
  page: (params) => http.get('/candidate', { params })
}

export const recruitApi = {
  score: (data) => http.post('/recruit/score', data),
  redo: (candidateId) => http.post(`/recruit/score/${candidateId}/redo`),
  greet: (data) => http.post('/recruit/greet', data),
  collect: (data) => http.post('/recruit/collect', data)
}

export const userJdApi = {
  list: (userId) => http.get(`/user/${userId}/jd`),
  assign: (userId, jdId) => http.post(`/user/${userId}/jd/${jdId}`),
  unassign: (userId, jdId) => http.delete(`/user/${userId}/jd/${jdId}`)
}
