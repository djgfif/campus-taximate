export const REQUEST_LABEL = {
  WAITING: '일행을 찾는 중',
  MATCHED: '일행 확정',
  COMPLETED: '탑승 완료',
  UNMATCHED: '미성사',
  CANCELED: '취소',
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
