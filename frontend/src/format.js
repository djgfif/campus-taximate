export const HISTORY_LABEL = {
  BOARDING_UNCONFIRMED: ['탑승 미확인, 소명 기간', 'warn'],
  BOARDING_REVIEW: ['탑승 미확인, 관리자 검토 중', 'warn'],
  BOARDING_NO_SHOW: ['노쇼 확정', 'bad'],
  BOARDING_EXONERATED: ['탑승 조사 결과 노쇼 아님', 'ok'],
  RIDE_UNCONFIRMED: ['운행 확인 중', 'warn'],
  PREPAYING: ['선결제 전', 'warn'], READY: ['모이는 중', 'warn'],
  AWAITING_FARE: ['금액 입력 대기', 'warn'], OBJECTION_WINDOW: ['요금 확인 기간', 'warn'],
  REVIEW_PENDING: ['관리자 검토 중', 'warn'], COMPLETED: ['완료', 'ok'],
  DISSOLVED: ['해산', ''], NOT_OPERATED: ['운행 안 됨', ''], FARE_MISSING: ['정산 미입력', 'bad'],
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

/** 이용 내역 항목의 실제 본인 분담금. 서버가 myShare를 주면 우선 쓰고, 결제자 전액(paid === partyPaid)만 있는 구형 데이터는 null을 돌려준다. */
export function historyMyShare(item) {
  if (!item) return null
  if (item.myShare != null) return item.myShare
  if (item.paid == null) return null
  if (item.payer && item.partyPaid != null && item.paid === item.partyPaid) return null
  return item.paid
}

/** 정산이 끝난 건에서 혼자 탈 때보다 아낀 금액의 합. */
export const savedTotal = (items) => items.filter((item) => item.stage === 'SETTLED')
  .reduce((sum, item) => {
    const share = historyMyShare(item)
    return share == null ? sum : sum + Math.max(0, item.soloFare - share)
  }, 0)

export const routeLabel = (item) =>
  item.direction === 'TO_SCHOOL' ? `${item.pointName}에서 ${item.anchorName}` : `${item.anchorName}에서 ${item.pointName}`

/** 일행 카드·목록용 경로 이름. 내 출발지/목적지가 있으면 승차 대기 위치 대신 실제 지점을 보여 준다. */
export function partyRouteLabel(party) {
  const mine = party.members?.find((m) => m.requestId === party.myRequestId)
  return routeLabel({ direction: party.direction, anchorName: party.anchorName, pointName: mine?.pointName || party.meetingPoint })
}

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

/**
 * 요청 전에 보여 줄 "같이 타면 이 금액 이하". 서버는 일행 합계가 혼자 요금 합의 (1 - 최소 절약률) 이하일 때만 묶고,
 * 몫은 혼자 요금 비율로 나누므로 내 몫도 그 이하다. 몫을 나눌 때 생기는 몇 원의 반올림(10원 여유)까지 덮고 100원 단위로 올린다.
 */
export const shareCap = (soloFare, minSavingRate) => Math.ceil((soloFare * (1 - minSavingRate) + 10) / 100) * 100

export const PARTY_LABEL = {
  CONFIRMED: '일행 확정', SETTLING: '이전 정산 기록', COMPLETED: '완료', UNSETTLED: '정산 미입력', CANCELED: '취소',
  RIDE_UNCONFIRMED: '탔는지 확인하는 중', PREPAYING: '선결제하는 중', READY: '모이는 중', PAYER_PENDING: '결제자 정하는 중',
  AWAITING_FARE: '요금 입력 기다리는 중', OBJECTION_WINDOW: '요금 확인 기간',
  REVIEW_PENDING: '관리자 검토 중', SETTLED: '정산 확정', DISSOLVED: '해산', NOT_OPERATED: '운행 안 됨', FARE_MISSING: '정산 미입력',
}
export const partyLabel = (status) => PARTY_LABEL[status] || '일행 상태 확인 중'
export const knownPartyStatus = (status) => PARTY_LABEL[status] != null
/** 결제 기반(v2 선결제·v3 후불) 일행인지. v3 후불은 선결제 마감이 없어도(prepayDeadlineAt: null) 결제 일행이다. */
export const isPaymentParty = (party) => Boolean(party && ((party.paymentFlowVersion ?? 0) >= 2
  || party.prepayDeadlineAt != null
  || ['RIDE_UNCONFIRMED', 'PREPAYING', 'READY', 'AWAITING_FARE', 'OBJECTION_WINDOW', 'REVIEW_PENDING', 'SETTLED', 'DISSOLVED', 'NOT_OPERATED', 'FARE_MISSING'].includes(party.status)
  || party.settlement?.objectionDeadlineAt != null))
/** 후불 정산 v3 일행인지. 등록된 결제수단으로 하차 뒤 청구한다. */
export const isPostpaidParty = (party) => (party?.paymentFlowVersion ?? 0) >= 3

/** 5분 단위 선택지는 서울 기준 다음 날 마지막 시각까지만 제공한다. */
export function requestTimeOptions(now, minimumMinutes = 25) {
  const stamp = new Date(now).getTime()
  if (!Number.isFinite(stamp)) return []
  const seoulDay = Math.floor((stamp + 9 * 3600000) / 86400000)
  const limit = (seoulDay + 2) * 86400000 - 9 * 3600000
  const first = Math.ceil((stamp + minimumMinutes * 60000) / 300000) * 300000
  return Array.from({ length: Math.max(0, Math.ceil((limit - first) / 300000)) }, (_, i) => new Date(first + i * 300000).toISOString())
}

/** 모이는 곳 이름 하나. 하교 거점은 "정확한 자리 (거점)", 등교는 내 승차 지점이다. 화면마다 이것만 쓴다. */
export function meetPlace(party) {
  if (!party) return ''
  const { anchorName: anchor, meetingPoint: spot, direction } = party
  if (direction === 'FROM_HUB' && anchor && spot && anchor !== spot) return `${spot} (${anchor})`
  return spot || anchor || ''
}
