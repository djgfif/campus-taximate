import React, { useState } from 'react'
import { ChevronLeft, Map, MessageCircle } from 'lucide-react'
import { api } from '../api.js'
import { PartyStepper, RideConfirmationCard } from '../PartyScreens.jsx'
import PaymentCard from '../PaymentCard.jsx'
import { roleTag } from '../PartyShell.jsx'
import { PraiseSection } from '../People.jsx'
import { FinancialDetails, LegacySettlement, ReceiptPaper } from '../SettleScreens.jsx'
import { toTripViewModel } from '../domain/tripViewModel.js'
import { formatDateTime, formatTime, isPaymentParty, meetPlace, partyLabel, remaining } from '../format.js'
import { Btn, Say, Screen, Sheet } from '../ui.jsx'

/**
 * 정산 상세 페이지 (/parties/:id, settlement 존재 시).
 * TripDetailPage와 동일한 8:4 PC 업무 레이아웃을 사용하며 채팅과 분리되어 있다.
 */
export default function SettlementPage({ party, myId, now, config, onUpdated, onHome, onCorrect, onDetail, onMessages }) {
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')

  if (!isPaymentParty(party)) return <LegacySettlement party={party} onHome={onHome} />

  const vm = toTripViewModel(party, myId)
  const settlement = party.settlement
  const deadline = settlement?.objectionDeadlineAt
  const financialHold = party.reviewReason === 'RIDE_FINANCIAL_HOLD'
  const open = settlement && !financialHold && ['OBJECTION_WINDOW', 'REVIEW_PENDING'].includes(party.status) && new Date(now) < new Date(deadline)

  const send = async () => {
    setError('')
    try {
      await api.objectToFare(party.id, reason.trim())
      setAsking(false)
      await onUpdated?.()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <Screen>
      <div className="trip-page">
        <header className="trip-header">
          <button type="button" className="trip-back" onClick={onHome}>
            <ChevronLeft size={18} aria-hidden="true" />
            <span>내 일행</span>
          </button>
          <div className="trip-header-main">
            <div className="trip-header-top">
              <span className="trip-role-pill">{roleTag(party)}</span>
              <span className="trip-status-pill">{vm?.statusLabel || partyLabel(party.status)}</span>
            </div>
            <h1 className="trip-title">
              {partyLabel(party.status)} · {formatTime(party.departureAt)} {meetPlace(party)}
            </h1>
          </div>
          <PartyStepper party={party} />
        </header>

        <div className="trip-grid">
          <div className="trip-main">
            <section className="home-card" aria-label="정산 내역">
              <h2 className="section-title">정산 요약</h2>
              <FinancialDetails party={party} />
            </section>

            {settlement && (
              <section className="home-card" aria-label="영수증 및 경로">
                <ReceiptPaper party={party} />
                {onDetail && (
                  <div className="trip-tool-row">
                    <Btn className="line small" onClick={onDetail}>
                      <Map size={15} aria-hidden="true" />
                      일행과 경로 자세히
                    </Btn>
                  </div>
                )}
              </section>
            )}

            <section className="home-card" aria-label="동승 칭찬">
              <PraiseSection partyId={party.id} now={now} />
            </section>
          </div>

          <aside className="trip-aside">
            <section className="home-card trip-action-card" aria-label="정산 확인 및 조치">
              <h2 className="block-title">정산 진행</h2>

              {settlement?.warning && (
                <Say tone="stop-tone">
                  실제 요금이 예상과 {Math.abs(settlement.differencePercent)}% 달라요. 영수증을 확인해 주세요.
                </Say>
              )}

              {open && (
                <Say title={`요금 이의 기간 ${remaining(deadline, now)} 남음`}>
                  {formatDateTime(deadline)}까지 이의를 낼 수 있어요.{' '}
                  {party.status === 'REVIEW_PENDING'
                    ? '접수된 이의는 관리자 검토를 기다려요.'
                    : '이의가 없으면 정산을 확정해요.'}
                </Say>
              )}

              {party.status === 'REVIEW_PENDING' && (
                <Say>
                  {financialHold
                    ? '운행 판정은 저장됐어요. 관리자가 결제 기록을 확인할 때까지 정산을 보류해요.'
                    : party.reviewReason === 'RIDE_DISPUTE'
                      ? '운행 여부를 관리자 검토 중이에요. 환불을 보류해요.'
                      : party.reviewReason === 'FARE_EXCEPTION'
                        ? '실제 요금이 확정 금액과 크게 달라 관리자가 영수증을 확인하고 있어요. 각자 낼 돈은 그대로예요.'
                        : '요금 이의가 있어 관리자 검토 중이에요. 환불과 받을 돈의 정산을 보류해요.'}
                </Say>
              )}

              <RideConfirmationCard party={party} onUpdated={onUpdated} />

              {settlement?.finalizedAt && settlement.payments?.filter((o) => o.purpose === 'ADDITIONAL').map((order) => (
                <PaymentCard
                  key={order.orderId}
                  knownOrder={order}
                  partyId={party.id}
                  purpose="ADDITIONAL"
                  myId={myId}
                  now={now}
                  config={config}
                  onUpdated={onUpdated}
                />
              ))}

              {!open && party.status === 'SETTLED' && !settlement?.payments?.some((o) => o.purpose === 'ADDITIONAL' && o.status === 'REQUIRED') && (
                <Say tone="save-tone" title="정산이 완료됐어요">
                  모든 분담금 정산 기록이 확정됐어요. 이용 내역에서 언제든 다시 확인할 수 있어요.
                </Say>
              )}

              <div className="trip-tool-row">
                {open && <Btn className="line" onClick={() => setAsking(true)}>요금 이의</Btn>}
                {settlement?.canCorrect && onCorrect && <Btn className="line" onClick={onCorrect}>금액 고치기</Btn>}
              </div>
            </section>

            <section className="home-card trip-msg-card" aria-label="일행 메시지">
              <div className="home-card-head">
                <h2 className="block-title">일행 메시지</h2>
                {party.unreadChat > 0 && (
                  <span className="unread-badge" aria-label={`안 읽은 메시지 ${party.unreadChat}개`}>
                    {party.unreadChat > 99 ? '99+' : party.unreadChat}
                  </span>
                )}
              </div>
              <p className="note">정산 관련 안내나 분실물 확인은 일행 메시지함에서 나눌 수 있어요.</p>
              <Btn className="line" onClick={() => onMessages?.(party.id)}>
                <MessageCircle size={17} aria-hidden="true" />
                일행 메시지 열기
              </Btn>
            </section>
          </aside>
        </div>
      </div>

      {asking && (
        <Sheet title="요금 이의" onClose={() => setAsking(false)}>
          <label className="field">
            이의 사유
            <textarea value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
          </label>
          {error && <p role="alert">{error}</p>}
          <div className="btns">
            <Btn className="line" onClick={() => setAsking(false)}>취소</Btn>
            <Btn disabled={!reason.trim()} onClick={send}>이의 보내기</Btn>
          </div>
        </Sheet>
      )}
    </Screen>
  )
}
