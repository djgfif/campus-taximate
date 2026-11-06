export class ApiError extends Error {
  constructor(status, code, message) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function request(path, options = {}) {
  const response = await fetch(`/api${path}`, { credentials: 'same-origin', ...options })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiError(response.status, body.code, body.message || '요청을 처리하지 못했습니다.')
  }
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

const json = (method, path, body) =>
  request(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  })

export const api = {
  config: () => request('/config'),
  me: () => request('/me'),
  state: () => request('/state'),
  hubs: () => request('/hubs'),
  history: () => request('/history'),
  signup: (body) => json('POST', '/auth/signup', body),
  verify: (body) => json('POST', '/auth/verify', body),
  login: (body) => json('POST', '/auth/login', body),
  logout: () => json('POST', '/auth/logout'),
  createRequest: (body) => json('POST', '/requests', body),
  cancelRequest: (id) => request(`/requests/${id}`, { method: 'DELETE' }),
  arrive: (partyId) => json('POST', `/parties/${partyId}/arrive`),
  submitSettlement: (partyId, amount, receipt) => {
    const form = new FormData()
    form.append('amount', amount)
    form.append('receipt', receipt)
    return request(`/parties/${partyId}/settlement`, { method: 'POST', body: form })
  },
  correctSettlement: (partyId, amount, receipt) => {
    const form = new FormData()
    form.append('amount', amount)
    form.append('receipt', receipt)
    return request(`/parties/${partyId}/settlement/correction`, { method: 'POST', body: form })
  },
  sent: (transferId, revision) => json('POST', `/transfers/${transferId}/sent`, { revision }),
  received: (transferId) => json('POST', `/transfers/${transferId}/received`),
  demoRun: () => json('POST', '/demo/run'),
  demoAdvance: (minutes) => json('POST', '/demo/advance', { minutes }),
  demoSamples: (count) => json('POST', '/demo/samples', { count }),
  demoAct: () => json('POST', '/demo/act'),
}
