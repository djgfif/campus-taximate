import { partyLabel } from '../format.js'

/**
 * 16개 내부 PartyStatus를 사용자 중심 진행 단계와 역할별 행동으로 투영한다.
 * 표시 전용 어댑터이며, 실제 허용 행동은 서버 권한 플래그(canSettle, canCall 등)를 따른다.
 */
export const TRIP_STAGES = [
  { id: 'CONFIRMED', label: '일행 확정' },
  { id: 'PAYMENT', label: '결제' },
  { id: 'PREPARATION', label: '승차 준비' },
  { id: 'BOARDING', label: '탑승 인증' },
  { id: 'RIDE', label: '운행' },
  { id: 'SETTLEMENT', label: '정산' },
  { id: 'DONE', label: '이용 완료' },
]

export function stageOfParty(party) {
  if (!party) return 'CONFIRMED'
  const status = party.status
  if (['DISSOLVED', 'NOT_OPERATED', 'CANCELED'].includes(status)) return 'CANCELED'
  if (['SETTLED', 'COMPLETED'].includes(status)) return 'DONE'
  if (['AWAITING_FARE', 'OBJECTION_WINDOW', 'REVIEW_PENDING', 'SETTLING', 'UNSETTLED', 'FARE_MISSING'].includes(status)) {
    return 'SETTLEMENT'
  }
  if (status === 'RIDE_UNCONFIRMED' || party.startedAt) return 'RIDE'
  if (party.calledAt || party.boardedAt) return 'BOARDING'
  if (['READY', 'PAYER_PENDING'].includes(status)) return 'PREPARATION'
  if (status === 'PREPAYING') return 'PAYMENT'
  return 'CONFIRMED'
}

export function nextActionOf(party, myId) {
  if (!party) return null
  if (party.status === 'PAYER_PENDING') {
    if (party.successorId === myId) {
      return { kind: 'SUCCESSION', label: '결제자 승계 응답이 필요해요', deadlineAt: party.successionDeadlineAt || null }
    }
    return { kind: 'WAIT_SUCCESSION', label: '새 결제자를 정하고 있어요', deadlineAt: party.successionDeadlineAt || null }
  }
  if (party.canSettle) {
    return { kind: 'SUBMIT_FARE', label: '실제 택시요금과 영수증을 입력해 주세요', deadlineAt: null }
  }
  if (party.status === 'PREPAYING') {
    if (!party.payer && !party.myPrepaid) {
      return { kind: 'PREPAY', label: '분담금 결제를 마쳐 주세요', deadlineAt: party.prepayDeadlineAt || null }
    }
    return { kind: 'WAIT_PREPAY', label: '다른 동승자의 결제를 기다리고 있어요', deadlineAt: party.prepayDeadlineAt || null }
  }
  if (party.canConfirmRide || party.status === 'RIDE_UNCONFIRMED') {
    return { kind: 'CONFIRM_RIDE', label: '실제 탑승 여부를 확인해 주세요', deadlineAt: null }
  }
  if (party.status === 'READY') {
    if (party.payer && party.canCall) {
      return { kind: 'CALL_TAXI', label: '모두 모이면 택시를 호출해 주세요', deadlineAt: null }
    }
    return { kind: 'MEET', label: '승차 지점에 모이는 중이에요', deadlineAt: party.departureAt || null }
  }
  if (party.settlement) {
    return { kind: 'VIEW_SETTLEMENT', label: '정산 내역을 확인해 주세요', deadlineAt: party.settlement.objectionDeadlineAt || null }
  }
  return null
}

/**
 * 이용 내역의 실부담금 계산.
 * 결제자는 택시에 전액(partyPaid)을 먼저 내고 동승자 분담금(payout)을 돌려받으므로,
 * 실부담금 = 내 최종 몫(myShare)으로 계산해 실제 절약액을 정확히 표시한다.
 */
export function netShareOfHistory(item, party) {
  if (party?.settlement?.shares) {
    const mine = party.settlement.shares.find((s) => s.payer === party.payer && (party.payer || s.amount != null))
    if (party.payer && mine?.amount != null) return mine.amount
  }
  if (party?.myShare != null && party?.payer) return party.myShare
  return item?.paid ?? null
}

export function toTripViewModel(party, myId) {
  if (!party) return null
  const stage = stageOfParty(party)
  const role = party.payer ? 'PAYER' : 'PASSENGER'
  const attention = []

  if (party.adminReviewRequired) {
    attention.push({ kind: 'ADMIN_REVIEW', severity: 'warning', message: '관리자 검토가 진행 중이에요.' })
  }
  if (party.canLeaveAfterChange) {
    attention.push({ kind: 'GROUP_CHANGED', severity: 'info', message: '일행 인원이 변경됐어요. 조건을 확인해 주세요.' })
  }
  if (['DISSOLVED', 'NOT_OPERATED', 'CANCELED'].includes(party.status)) {
    attention.push({ kind: 'CANCELED', severity: 'critical', message: partyLabel(party.status) })
  }

  return {
    partyId: party.id,
    stage,
    role,
    statusLabel: partyLabel(party.status),
    nextAction: nextActionOf(party, myId),
    attention,
    unreadChat: party.unreadChat || 0,
  }
}
