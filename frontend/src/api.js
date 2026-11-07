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

function settlementForm({ amount, receipt, bankName, accountNumber }) {
  const form = new FormData()
  form.append('amount', amount)
  form.append('receipt', receipt)
  form.append('bankName', bankName)
  form.append('accountNumber', accountNumber)
  return form
}

export const api = {
  config: () => request('/config'),
  me: () => request('/me'),
  state: () => request('/state'),
  hubs: () => request('/hubs'),
  school: () => request('/school'),
  history: () => request('/history'),
  requestCode: (body) => json('POST', '/auth/code', body),
  profile: (body) => json('POST', '/auth/profile', body),
  login: (body) => json('POST', '/auth/login', body),
  logout: () => json('POST', '/auth/logout'),
  logoutAll: () => json('POST', '/auth/logout-all'),
  createRequest: (body) => json('POST', '/requests', body),
  previewRequest: (body) => json('POST', '/requests/preview', body),
  cancelRequest: (id) => request(`/requests/${id}`, { method: 'DELETE' }),
  partyRoutes: (partyId) => request(`/parties/${partyId}/routes`),
  arrive: (partyId) => json('POST', `/parties/${partyId}/arrive`),
  boarded: (partyId) => json('POST', `/parties/${partyId}/boarded`),
  settlementPreview: (partyId, amount) => request(`/parties/${partyId}/settlement/preview?amount=${amount}`),
  submitSettlement: (partyId, fields) =>
    request(`/parties/${partyId}/settlement`, { method: 'POST', body: settlementForm(fields) }),
  correctSettlement: (partyId, fields) =>
    request(`/parties/${partyId}/settlement/correction`, { method: 'POST', body: settlementForm(fields) }),
  sent: (transferId, revision) => json('POST', `/transfers/${transferId}/sent`, { revision }),
  received: (transferId) => json('POST', `/transfers/${transferId}/received`),
  demoMatch: () => json('POST', '/demo/match'),
  pushKey: () => request('/push/key'),
  subscribePush: (body) => json('POST', '/push/subscription', body),
  unsubscribePush: (body) => json('DELETE', '/push/subscription', body),
  fareAccuracy: () => request('/stats/fare-accuracy'),
  demoAdvance: (minutes) => json('POST', '/demo/advance', { minutes }),
  demoSamples: (count) => json('POST', '/demo/samples', { count }),
  demoAct: () => json('POST', '/demo/act'),
}
