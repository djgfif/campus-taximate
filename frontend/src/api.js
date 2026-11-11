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

function settlementForm({ amount, receipt }) {
  const form = new FormData()
  form.append('amount', amount)
  form.append('receipt', receipt)
  return form
}

export const api = {
  partyMessages: (partyId, after) => request(`/parties/${partyId}/messages${after == null ? '' : `?after=${after}`}`),
  sendPartyMessage: (partyId, text) => json('POST', `/parties/${partyId}/messages`, { text }),
  config: () => request('/config'),
  me: () => request('/me'),
  state: () => request('/state'),
  hubs: () => request('/hubs'),
  nearbyPoints: ({ lat, lon }) => request(`/hubs/nearby?${new URLSearchParams({ lat, lon })}`),
  prepareParty: (id, state) => json('POST', `/parties/${id}/readiness`, { state }),
  callParty: (id) => json('POST', `/parties/${id}/call`),
  confirmRide: (id, rode) => json('POST', `/parties/${id}/ride-confirmation`, { rode }),
  leaveAfterChange: (id, groupVersion) => json('POST', `/parties/${id}/leave-after-change`, { groupVersion }),
  party: (id) => request(`/parties/${id}`),
  paymentOrder: (partyId, purpose) => json('POST', '/payments/orders', { partyId, purpose }),
  confirmPayment: (body) => json('POST', '/payments/confirm', body),
  getPayment: (orderId) => request(`/payments/orders/${encodeURIComponent(orderId)}`),
  objectToFare: (partyId, reason) => json('POST', `/parties/${partyId}/settlement/objections`, { reason }),
  school: () => request('/school'),
  history: () => request('/history'),
  partyMembers: (partyId) => request(`/parties/${partyId}/members`),
  reportMember: (partyId, memberId, body) => json('POST', `/parties/${partyId}/members/${memberId}/report`, body),
  avoidMember: (memberId) => json('PUT', `/members/${memberId}/avoid`),
  unavoidMember: (memberId) => request(`/members/${memberId}/avoid`, { method: 'DELETE' }),
  avoids: () => request('/avoids'),
  requestCode: (body) => json('POST', '/auth/code', body),
  profile: (body) => json('POST', '/auth/profile', body),
  login: (body) => json('POST', '/auth/login', body),
  signin: (body) => json('POST', '/auth/signin', body),
  setPassword: (body) => json('POST', '/auth/password', body),
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
