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
  return new Date(value).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })
}

export function formatDateTime(value) {
  return new Date(value).toLocaleString('ko-KR', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

export const won = (value) => `${Number(value).toLocaleString()}원`
