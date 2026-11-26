import React, { useState } from 'react'
import { ChevronLeft, Map, MessageCircle } from 'lucide-react'
import { api } from '../api.js'
import { PartyStepper, RideConfirmationCard } from '../PartyScreens.jsx'
import PaymentCard from '../PaymentCard.jsx'
import { roleTag } from '../PartyShell.jsx'
import { PraiseSection } from '../People.jsx'
import { FinancialDetails, LegacySettlement, PostpaidSettlementPanel, ReceiptPaper, SettlementNotices, settlementWindow } from '../SettleScreens.jsx'
import { toTripViewModel } from '../domain/tripViewModel.js'
import { formatTime, isPaymentParty, isPostpaidParty, meetPlace, partyLabel } from '../format.js'
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
  const postpaid = isPostpaidParty(party)
  const { open, canObject } = settlementWindow(party, now)
  const pendingPayment = postpaid
    ? settlement?.payments?.some((o) => ['POSTPAID', 'COMPENSATION', 'ADDITIONAL'].includes(o.purpose) && ['REQUIRED', 'OVERDUE', 'PROCESSING'].includes(o.status))
    : settlement?.payments?.some((o) => o.purpose === 'ADDITIONAL' && o.status === 'REQUIRED')

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

              <SettlementNotices party={party} now={now} />

              <RideConfirmationCard party={party} onUpdated={onUpdated} />

              {postpaid && <PostpaidSettlementPanel party={party} myId={myId} now={now} config={config} onUpdated={onUpdated} />}

              {!postpaid && settlement?.finalizedAt && settlement.payments?.filter((o) => o.purpose === 'ADDITIONAL').map((order) => (
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

              {!open && party.status === 'SETTLED' && !pendingPayment && (
                <Say tone="save-tone" title="정산이 완료됐어요">
                  모든 분담금 정산 기록이 확정됐어요. 이용 내역에서 언제든 다시 확인할 수 있어요.
                </Say>
              )}

              <div className="trip-tool-row">
                {canObject && <Btn className="line" onClick={() => setAsking(true)}>요금 이의</Btn>}
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
