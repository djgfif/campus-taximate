import React, { useEffect, useState } from 'react'
import { ChevronDown, ChevronRight, ChevronUp } from 'lucide-react'
import { api } from './api.js'
import { POSTPAID_RECORD_LABEL, PostpaidCollection } from './SettleScreens.jsx'
import { PaymentRecord } from './PaymentCard.jsx'
import { MemberActions } from './MemberSafety.jsx'
import { HISTORY_LABEL, formatDateTime, historyMyShare, isPostpaidParty, savedTotal, won } from './format.js'
import { Bone, Btn, Empty, Say, Screen, useWide } from './ui.jsx'

const ongoing = (item) => (HISTORY_LABEL[item.stage] || [])[1] === 'warn'

const FILTER_TABS = [
  ['ALL', '전체'],
  ['ONGOING', '진행 중'],
  ['PAST', '지난 이용'],
]

const historyRoute = (item) => (item.direction === 'TO_SCHOOL'
  ? `${item.pointName} → ${item.anchorName}`
  : `${item.anchorName} → ${item.pointName}`)

function shortHistoryDate(value) {
  const date = new Date(value)
  if (value == null || !Number.isFinite(date.getTime())) return '시각 확인 중'
  const parts = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  }).formatToParts(date)
  const get = (type) => parts.find((p) => p.type === type)?.value || ''
  return `${get('month')}월 ${get('day')}일 ${get('dayPeriod')} ${get('hour')}:${get('minute')}`
}

function PaymentVoucherBox({ order, label }) {
  const needsAttention = order.status !== 'APPROVED'
    || (order.refundedAmount || 0) > 0
    || (order.refundRequestedAmount || 0) > 0
    || Boolean(order.refundHeld)
  const [open, setOpen] = useState(needsAttention)
  const amountText = order.approvedAmount > 0 ? won(order.approvedAmount) : won(order.amount)
  const statusText = ({
    REQUIRED: '결제 대기',
    PROCESSING: '확인 중',
    APPROVED: '승인 완료',
    EXPIRED: '기한 종료',
    OVERDUE: '기한 지남',
    ADMIN_REQUIRED: '관리자 확인 필요',
  })[order.status] || '확인 중'

  return (
    <div className="history-voucher-box">
      <div className="history-voucher-summary">
        <span><strong>{label}</strong> · {statusText} · {amountText}</span>
        <button
          type="button"
          className="history-voucher-toggle"
          aria-expanded={open}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? '접기' : '영수증 보기'}
        </button>
      </div>
      {open && (
        <div className="history-voucher-body">
          <PaymentRecord order={order} />
          {(order.refundRequestedAmount > 0 || order.refundedAmount > 0) && (
            <p className="note">환불은 카드 반영까지 3~4영업일 정도 걸려요.</p>
          )}
        </div>
      )}
    </div>
  )
}

function HistoryCard({ item, wide, selected, onSelect }) {
  const [label, tone] = HISTORY_LABEL[item.stage] || ['상태 확인 중', '']
  const myShare = historyMyShare(item)
  const saved = myShare != null && item.soloFare > myShare ? item.soloFare - myShare : 0
  const isActionPending = ongoing(item) || item.stage?.startsWith('BOARDING_') || item.stage?.startsWith('RIDE_')

  const cardProps = wide
    ? { 'aria-pressed': selected }
    : { 'aria-expanded': selected }

  return (
    <button type="button" className="history-card" {...cardProps} onClick={onSelect}>
      <div className="history-card-top">
        <div className="history-card-badges">
          <span className={`badge ${tone}`}>{label}</span>
          {item.payer && <span className="history-role-meta">내가 결제</span>}
        </div>
        <span className="history-card-date">
          {shortHistoryDate(item.arriveBy || item.windowStart)}
          {wide
            ? <ChevronRight size={15} aria-hidden="true" />
            : selected ? <ChevronUp size={15} aria-hidden="true" /> : <ChevronDown size={15} aria-hidden="true" />}
        </span>
      </div>
      <div className="history-card-bottom">
        <div className="history-card-route">
          <b>{historyRoute(item)}</b>
          {item.partySize != null && <small>{item.partySize}명 동승</small>}
        </div>
        <div className="history-card-money">
          {myShare != null ? (
            <>
              <strong>{won(myShare)}</strong>
              {saved > 0 && <span>{won(saved)} 절약</span>}
            </>
          ) : item.partyPaid != null ? (
            <strong>총 {won(item.partyPaid)}</strong>
          ) : isActionPending ? (
            <span className="history-card-pending">정산 전</span>
          ) : null}
        </div>
      </div>
    </button>
  )
}

function BoardingCaseLink({ item, onBoardingCases }) {
  if (!item.stage?.startsWith('BOARDING_')) return null
  return (
    <div className="case-link">
      {item.stage === 'BOARDING_UNCONFIRMED' && (
        <Say tone="stop-tone">번호 미입력만으로 노쇼를 확정하지 않아요. 24시간 안에 당시 상황을 소명할 수 있어요.</Say>
      )}
      <Btn className="small line" onClick={onBoardingCases || (() => window.location.assign('/boarding-cases'))}>탑승 확인</Btn>
    </div>
  )
}

function HistoryDetail({ item, showHeader = true, onBoardingCases, onOpenParty, onOpenMessages }) {
  const id = item.partyId
  const own = !id
  const available = Boolean(id || item.partyIds?.length || item.endReason)
  const key = `${item.id}:${id || 'own'}`
  const [result, setResult] = useState({})
  const [reload, setReload] = useState(0)
  const [safetyOpen, setSafetyOpen] = useState(false)

  useEffect(() => {
    setSafetyOpen(false)
  }, [key])

  useEffect(() => {
    if (!available) return undefined
    let closed = false
    const load = own ? api.ownPaymentHistory(item.id) : api.party(id)
    load.then((data) => !closed && setResult(own ? { key, orders: data } : { key, party: data }))
      .catch((e) => !closed && setResult({ key, error: e.message }))
    return () => { closed = true }
  }, [id, item.id, own, available, key, reload])

  const current = result.key === key ? result : {}
  const party = current.party || null
  const settlement = party?.settlement || null
  const ownId = party?.members?.find((m) => m.requestId === party?.myRequestId)?.memberId
  const ownShareRow = settlement?.shares?.find((s) => s.memberId === ownId)
  const isPayer = party ? Boolean(party.payer) : Boolean(item.payer)
  const myShare = settlement
    ? (ownShareRow?.amount ?? (isPayer && party?.myShare != null ? party.myShare : null) ?? historyMyShare(item))
    : historyMyShare(item)
  const partyPaid = settlement?.paidAmount ?? item.partyPaid ?? null
  const saving = myShare != null && item.soloFare > myShare ? item.soloFare - myShare : 0
  const [label, tone] = HISTORY_LABEL[item.stage] || ['상태 확인 중', '']
  const isOngoing = ongoing(item)
  const postpaid = party ? isPostpaidParty(party) : true
  const collection = Boolean(postpaid && party?.payer && settlement?.expectedPayoutAmount != null)
  const hasPrepayHold = item.stage?.startsWith('BOARDING_') && current.orders?.some((o) => o.purpose === 'PREPAY' || o.refundHeld)

  const recordLabel = (purpose) => (postpaid
    ? POSTPAID_RECORD_LABEL[purpose] || '결제 기록'
    : purpose === 'ADDITIONAL' ? '분담금 결제 기록' : '선결제 기록')

  return (
    <div className="history-detail-panel">
      {showHeader && (
        <div className="history-detail-head">
          <div className="history-detail-badges">
            <span className={`badge ${tone}`}>{label}</span>
            {isPayer && <span className="history-role-meta">내가 결제</span>}
            {item.partySize != null && <span className="history-role-meta">{item.partySize}명 동승</span>}
          </div>
          <h2 className="history-detail-route">{historyRoute(item)}</h2>
          <p className="history-detail-meta">{shortHistoryDate(item.arriveBy || item.windowStart)}</p>
        </div>
      )}

      <div className="history-money-hero">
        <div className="history-money-top">
          <span className="history-money-label">
            {myShare != null ? '내 분담금' : isOngoing || item.stage?.startsWith('BOARDING_') ? '정산 현황' : '이용 금액'}
          </span>
          {myShare != null ? (
            <strong className="history-money-amount">{won(myShare)}</strong>
          ) : partyPaid != null ? (
            <strong className="history-money-amount">{won(partyPaid)}</strong>
          ) : isOngoing || item.stage?.startsWith('BOARDING_') || item.stage?.startsWith('RIDE_') ? (
            <strong className="history-money-amount">정산 전</strong>
          ) : (
            <strong className="history-money-amount">청구 요금 없음</strong>
          )}
          {saving > 0 && (
            <span className="history-money-saving">혼자 탈 때({won(item.soloFare)})보다 {won(saving)} 아꼈어요</span>
          )}
          {myShare == null && item.soloFare > 0 && (isOngoing || item.stage?.startsWith('BOARDING_')) && (
            <span className="history-money-label">혼자 타면 약 {won(item.soloFare)}</span>
          )}
        </div>
        {(partyPaid != null && partyPaid !== myShare) || settlement?.finalizedAt ? (
          <div className="history-money-meta">
            {partyPaid != null && partyPaid !== myShare && (
              <div>
                <span>{isPayer ? '실제 택시비 (현장 결제)' : '실제 택시비'}</span>
                <strong>{won(partyPaid)}</strong>
              </div>
            )}
            {settlement?.finalizedAt && (
              <div>
                <span>정산 확정</span>
                <strong>{formatDateTime(settlement.finalizedAt)}</strong>
              </div>
            )}
          </div>
        ) : null}
      </div>

      {id != null && (
        <div className="history-detail-actions">
          <Btn
            className={isOngoing ? 'block' : 'line block'}
            onClick={() => (onOpenParty ? onOpenParty(id) : window.location.assign(`/parties/${id}`))}
          >
            {isOngoing ? '일행·정산 화면 열기' : '일행 상세 보기'}
          </Btn>
          {onOpenMessages && (
            <Btn className="quiet" onClick={() => onOpenMessages(id)}>메시지 열기</Btn>
          )}
        </div>
      )}

      <BoardingCaseLink item={item} onBoardingCases={onBoardingCases} />

      <section className="history-finance" aria-label="결제와 환불 기록">
        <h3>결제와 환불 기록</h3>
        {!available && <p className="note">이 요청에서 조회할 수 있는 결제 상세가 없어요.</p>}
        {available && !current.party && !current.orders && !current.error && (
          <p role="status" className="note">결제 기록을 읽고 있어요.</p>
        )}
        {current.error && <p role="status">결제 상세를 읽지 못했어요. {current.error}</p>}

        {settlement && (
          <>
            {settlement.shares?.length > 0 && (
              <div className="history-subsection">
                <h4>각자 분담금</h4>
                <div className="history-shares-list">
                  {settlement.shares.map((s) => {
                    const isMe = s.memberId === ownId
                    const name = isMe ? '나' : party.members?.find((m) => m.memberId === s.memberId)?.nickname || '일행'
                    return (
                      <div key={s.memberId} className={isMe ? 'history-share-row me' : 'history-share-row'}>
                        <span>{name}{s.payer ? ' (결제자)' : ''}</span>
                        <span>{won(s.amount)}</span>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}

            {collection && (
              <div className="history-subsection">
                <h4>동승자 결제 수납</h4>
                {(settlement.totalRidersToConfirm ?? 0) > 0 ? (
                  <div
                    className="money-progress"
                    role="progressbar"
                    aria-label="확인·결제 진행률"
                    aria-valuemin={0}
                    aria-valuemax={settlement.totalRidersToConfirm}
                    aria-valuenow={settlement.confirmedCount ?? 0}
                  >
                    <div
                      className="money-progress-fill"
                      style={{ width: `${Math.min(100, Math.round(((settlement.confirmedCount ?? 0) / settlement.totalRidersToConfirm) * 100))}%` }}
                    />
                  </div>
                ) : (
                  <p className="note">탑승 확정한 동승자가 없어 수납할 금액이 없어요.</p>
                )}
                <PostpaidCollection settlement={settlement} />
              </div>
            )}

            {!collection && party.payer && settlement.finalizedAt && settlement.mockPayoutAmount != null && (
              <Say tone="calm" title={'앱 정산 기록상 받을 돈(실제 송금 없음) ' + won(settlement.mockPayoutAmount)}>
                <span className="mock-payout-meta">
                  <span className="badge">실제 송금 없음</span> 장부 기록용 금액으로 실제 계좌 송금은 진행되지 않아요.
                </span>
              </Say>
            )}

            {settlement.payments?.map((order) => (
              <PaymentVoucherBox key={order.orderId} order={order} label={recordLabel(order.purpose)} />
            ))}
          </>
        )}

        {party && !settlement && <p className="note">아직 정산 기록이 없어요.</p>}

        {current.orders && (
          <>
            {hasPrepayHold && (
              <p className="note">선결제는 조사 중 보류돼요. 노쇼가 아니면 전액, 노쇼면 보전 부담금을 뺀 나머지를 돌려받아요.</p>
            )}
            {current.orders.length === 0 && (
              <p className="note">{item.stage?.startsWith('BOARDING_') ? '후불 정산 방식이거나 결제된 주문이 없어요.' : '내 결제 주문이 없어요.'}</p>
            )}
            {current.orders.map((order) => (
              <section key={order.orderId} className="history-subsection" aria-label={order.purpose === 'ADDITIONAL' ? '분담금 결제 기록' : '선결제 기록'}>
                <h4>{order.purpose === 'ADDITIONAL' ? '분담금 결제 기록' : '선결제 기록'}</h4>
                <PaymentRecord order={order} />
                {order.refundHeld && <p className="note">{order.holdReason || '환불 보류 중'}</p>}
                {order.simulated && <p className="note">시연용 모의 결제 기록이에요. 실제 돈은 나가지 않아요.</p>}
              </section>
            ))}
          </>
        )}
      </section>

      <div className="history-detail-foot">
        {party?.canConfirmRide && (
          <Btn
            className="small line"
            onClick={() => (onOpenParty ? onOpenParty(id) : window.location.assign(`/parties/${id}`))}
          >
            일행 화면에서 운행 답변하기
          </Btn>
        )}
        <div className="history-foot-links">
          {(current.error || current.party || current.orders?.length > 0) && (
            <button type="button" className="history-text-action" onClick={() => setReload((n) => n + 1)}>
              {current.error ? '다시 시도' : '상태 새로고침'}
            </button>
          )}
          {id != null && (
            <button type="button" className="history-text-action" aria-expanded={safetyOpen} onClick={() => setSafetyOpen((v) => !v)}>
              일행 신고 · 차단
            </button>
          )}
        </div>
        {id != null && safetyOpen && <MemberActions partyId={id} />}
      </div>
    </div>
  )
}

/** 이용 내역. PC는 좌측 그룹 목록 면 + 우측 고정 상세, 모바일은 간결 그룹 리스트 + 펼침 상세. */
export default function History({ onHome, admin = false, onBoardingCases, onOpenParty, onOpenMessages, tabBar }) {
  const wide = useWide()
  const [items, setItems] = useState(null)
  const [selectedId, setSelectedId] = useState(undefined)
  const [filter, setFilter] = useState('ALL')
  const [accuracy, setAccuracy] = useState(null)

  useEffect(() => {
    api.history().then(setItems).catch(() => setItems([]))
    if (admin) api.fareAccuracy().then(setAccuracy).catch(() => setAccuracy(null))
  }, [admin])

  const saved = savedTotal(items || [])
  const ongoingItems = items ? items.filter(ongoing) : []
  const pastItems = items ? items.filter((item) => !ongoing(item)) : []
  const ordered = [...ongoingItems, ...pastItems]
  const visibleItems = filter === 'ONGOING' ? ongoingItems : filter === 'PAST' ? pastItems : ordered

  const activeId = selectedId === undefined
    ? visibleItems[0]?.id ?? null
    : selectedId != null && visibleItems.some((item) => item.id === selectedId)
      ? selectedId
      : wide ? (visibleItems[0]?.id ?? null) : null
  const selected = activeId != null ? visibleItems.find((item) => item.id === activeId) || null : null

  const toggleItem = (id) => {
    if (wide) {
      setSelectedId(id)
      return
    }
    setSelectedId((prev) => {
      const current = prev === undefined ? visibleItems[0]?.id ?? null : prev
      return current === id ? null : id
    })
  }

  const selectFilter = (next) => {
    setFilter(next)
    setSelectedId(undefined)
  }

  const groups = filter === 'ALL'
    ? [['진행 중', ongoingItems], ['지난 이용', pastItems]].filter(([, list]) => list.length > 0)
    : [[filter === 'ONGOING' ? '진행 중' : '지난 이용', visibleItems]].filter(([, list]) => list.length > 0)

  return (
    <Screen tabBar={tabBar}>
      <header className="bar"><h1 className="bar-title">이용 내역</h1></header>
      <div className="history-page">
        <h1 className="page-heading">이용 내역</h1>
        {!items && (
          <div className="history-list">
            <Bone height={76} /><Bone height={76} /><Bone height={76} />
          </div>
        )}
        {items?.length === 0 && (
          <Empty title="아직 탄 기록이 없어요" action="동승 찾기" onAction={onHome}>
            같이 타면 아낀 금액이 여기에 모여요.
          </Empty>
        )}
        {items?.length > 0 && (
          <>
            <div className="history-summary">
              <div className="history-summary-main">
                <span className="history-summary-label">같이 타서 아낀 돈</span>
                <strong className="history-summary-value">{won(saved)}</strong>
              </div>
              <div className="history-summary-meta">
                {ongoingItems.length > 0 && <span className="warn">진행 중 {ongoingItems.length}건 · </span>}
                <span>완료·종료 {pastItems.length}건</span>
              </div>
            </div>

            {admin && accuracy?.settled > 0 && (
              <section className="history-admin-note" aria-label="관리자 요금 통계">
                <h2>관리자 요금 통계</h2>
                <p>전체 정산 {accuracy.settled}건 중 {accuracy.within}건이 예상 요금의 15% 이내였어요.</p>
              </section>
            )}

            {items.length >= 2 && (
              <div className="history-filters" role="tablist" aria-label="이용 내역 필터">
                {FILTER_TABS.map(([key, text]) => {
                  const count = key === 'ALL' ? ordered.length : key === 'ONGOING' ? ongoingItems.length : pastItems.length
                  return (
                    <button
                      key={key}
                      type="button"
                      role="tab"
                      aria-selected={filter === key}
                      className={filter === key ? 'history-filter-btn on' : 'history-filter-btn'}
                      onClick={() => selectFilter(key)}
                    >
                      {text} <span className="history-filter-count">{count}</span>
                    </button>
                  )
                })}
              </div>
            )}

            {visibleItems.length === 0 ? (
              <Empty title={filter === 'ONGOING' ? '진행 중인 이용이 없어요' : '지난 이용이 없어요'} />
            ) : (
              <div className="history-layout">
                <div className="history-list">
                  {groups.map(([heading, list]) => (
                    <section key={heading} aria-label={heading} className="history-group-block">
                      <h2 className="history-group-heading">{heading}</h2>
                      <div className="history-group-surface">
                        {list.map((item) => {
                          const isSelected = item.id === selected?.id
                          return (
                            <div key={item.id} className={isSelected ? 'history-card-wrap is-selected' : 'history-card-wrap'}>
                              <HistoryCard
                                item={item}
                                wide={wide}
                                selected={isSelected}
                                onSelect={() => toggleItem(item.id)}
                              />
                              {!wide && isSelected && (
                                <div className="history-card-inline-detail">
                                  <HistoryDetail
                                    item={item}
                                    showHeader={false}
                                    onBoardingCases={onBoardingCases}
                                    onOpenParty={onOpenParty}
                                    onOpenMessages={onOpenMessages}
                                  />
                                </div>
                              )}
                            </div>
                          )
                        })}
                      </div>
                    </section>
                  ))}
                </div>

                {wide && selected && (
                  <aside className="history-detail-pane" aria-label="선택한 이용 상세">
                    <HistoryDetail
                      item={selected}
                      onBoardingCases={onBoardingCases}
                      onOpenParty={onOpenParty}
                      onOpenMessages={onOpenMessages}
                    />
                  </aside>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </Screen>
  )
}
