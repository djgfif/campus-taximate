export const HISTORY_LABEL = {
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
  return new Date(value).toLocaleTimeString('ko-KR', { hour: 'numeric', minute: '2-digit', hour12: true })
}

/** 서버 시계 기준 남은 시간. 시연에서는 브라우저 시계와 다르다. */
export function remaining(target, now) {
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
  return new Date(value).toLocaleString('ko-KR', {
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  })
}

export const won = (value) => `${Number(value).toLocaleString()}원`
