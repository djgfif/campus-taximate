export const HISTORY_LABEL = {
  RIDE_UNCONFIRMED: ['운행 확인 중', 'warn'],
  PREPAYING: ['선결제 대기', 'warn'], READY: ['호출 대기', 'warn'],
  AWAITING_FARE: ['금액 입력 대기', 'warn'], OBJECTION_WINDOW: ['이의 기간', 'warn'],
  REVIEW_PENDING: ['관리자 검토 중', 'warn'], COMPLETED: ['완료', 'ok'],
  DISSOLVED: ['해산', ''], NOT_OPERATED: ['운행 미성사', 'bad'], FARE_MISSING: ['정산 미입력', 'bad'],
  WAITING: ['일행 찾는 중', 'warn'],
  MATCHED: ['일행 확정', 'warn'],
  AWAITING_AMOUNT: ['금액 입력 대기', 'warn'],
  SETTLING: ['정산 중', 'warn'],
  SETTLED: ['정산 완료', 'ok'],
  UNSETTLED: ['정산 미입력', 'bad'],
  UNMATCHED: ['미성사', ''],
  CANCELED: ['취소', ''],
  NO_SHOW: ['노쇼', 'bad'],
  PARTY_CANCELED: ['일행 취소', ''],
  LEFT_ALONE: ['혼자 이동', ''],
}

export const TRANSFER_LABEL = {
  REQUESTED: '보내기 전',
  SENT: '보냄',
  RECEIVED: '받음',
}

export function formatTime(value) {
  if (value == null || !Number.isFinite(new Date(value).getTime())) return '시각 확인 중'
  return new Date(value).toLocaleTimeString('ko-KR', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone: 'Asia/Seoul' })
}

/** 서버 시계 기준 남은 시간. 시연에서는 브라우저 시계와 다르다. */
export function remaining(target, now) {
  if (target == null || now == null || !Number.isFinite(new Date(target).getTime() - new Date(now).getTime())) return '확인 중'
  const minutes = Math.max(0, Math.ceil((new Date(target) - new Date(now)) / 60000))
  if (minutes < 60) return `${minutes}분`
  const rest = minutes % 60
  return rest ? `${Math.floor(minutes / 60)}시간 ${rest}분` : `${Math.floor(minutes / 60)}시간`
}

/** 정산이 끝난 건에서 혼자 탈 때보다 아낀 금액의 합. */
export const savedTotal = (items) => items.filter((item) => item.stage === 'SETTLED' && item.paid != null)
  .reduce((sum, item) => sum + Math.max(0, item.soloFare - item.paid), 0)

export const routeLabel = (item) =>
  item.direction === 'TO_SCHOOL' ? `${item.pointName}에서 ${item.anchorName}` : `${item.anchorName}에서 ${item.pointName}`

export function formatDateTime(value) {
  if (value == null || !Number.isFinite(new Date(value).getTime())) return '시각 확인 중'
  return new Date(value).toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  })
}

export const won = (value) => `${Number(value).toLocaleString()}원`

export const PARTY_LABEL = {
  CONFIRMED: '일행 확정', SETTLING: '이전 정산 기록', COMPLETED: '완료', UNSETTLED: '정산 미입력', CANCELED: '취소',
  RIDE_UNCONFIRMED: '운행 확인 중', PREPAYING: '선결제 대기', READY: '호출 대기', AWAITING_FARE: '금액 입력 대기', OBJECTION_WINDOW: '이의 기간',
  REVIEW_PENDING: '관리자 검토 중', SETTLED: '정산 확정', DISSOLVED: '해산', NOT_OPERATED: '운행 미성사', FARE_MISSING: '정산 미입력',
}
export const partyLabel = (status) => PARTY_LABEL[status] || '일행 상태 확인 중'
export const knownPartyStatus = (status) => PARTY_LABEL[status] != null
export const isPaymentParty = (party) => party.prepayDeadlineAt != null
  || ['RIDE_UNCONFIRMED', 'PREPAYING', 'READY', 'AWAITING_FARE', 'OBJECTION_WINDOW', 'REVIEW_PENDING', 'SETTLED', 'DISSOLVED', 'NOT_OPERATED', 'FARE_MISSING'].includes(party.status)
  || party.settlement?.objectionDeadlineAt != null

/** 5분 단위 선택지는 서울 기준 다음 날 마지막 시각까지만 제공한다. */
export function requestTimeOptions(now, minimumMinutes = 25) {
  const stamp = new Date(now).getTime()
  if (!Number.isFinite(stamp)) return []
  const seoulDay = Math.floor((stamp + 9 * 3600000) / 86400000)
  const limit = (seoulDay + 2) * 86400000 - 9 * 3600000
  const first = Math.ceil((stamp + minimumMinutes * 60000) / 300000) * 300000
  return Array.from({ length: Math.max(0, Math.ceil((limit - first) / 300000)) }, (_, i) => new Date(first + i * 300000).toISOString())
}

/** 모이는 곳 이름 하나. 하교 거점은 "거점 · 정확한 자리", 등교는 내 승차 지점이다. 화면마다 이것만 쓴다. */
export function meetPlace(party) {
  if (!party) return ''
  const { anchorName: anchor, meetingPoint: spot, direction } = party
  if (direction === 'FROM_HUB' && anchor && spot && anchor !== spot) return `${anchor} · ${spot}`
  return spot || anchor || ''
}
