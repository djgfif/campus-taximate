import React, { useState } from 'react'
import { ChevronLeft, Map, MessageCircle, ShieldAlert } from 'lucide-react'
import { api } from '../api.js'
import BoardingCodePanel from '../BoardingCodePanel.jsx'
import { MemberActions } from '../MemberSafety.jsx'
import { CHAT_RETENTION } from '../PartyChat.jsx'
import { NextAction, PartyFareRows, PartyStepper } from '../PartyScreens.jsx'
import { CancelSheet, DriverView, PartySecondary, roleTag } from '../PartyShell.jsx'
import { trustLine, usePeople } from '../People.jsx'
import { BoardingPanel } from '../ReviewScreens.jsx'
import RouteComparison, { figures } from '../RouteComparison.jsx'
import { toTripViewModel } from '../domain/tripViewModel.js'
import { formatDateTime, formatTime, isPaymentParty, isPostpaidParty, meetPlace, partyLabel, won } from '../format.js'
import { Avatar, Btn, FairnessNote, FareCompare, Fold, Rail, Rows, Say, Screen, Sheet } from '../ui.jsx'

/**
 * 이용 상세 페이지 (/app/trips/:partyId, /parties/:id).
 * 채팅과 분리된 업무 중심 화면: PC에서는 8:4 2단 레이아웃으로
 * 왼쪽에 이동·일행·금액·경로 정보를, 오른쪽에 역할별 '지금 할 일'과 메시지함 링크를 배치한다.
 */
export default function TripDetailPage({ party, now, myId, config, run, onUpdated, onHome, onDetail, onMessages }) {
  const [asking, setAsking] = useState(false)
  const [driver, setDriver] = useState(false)
  const [leavingChange, setLeavingChange] = useState(false)
  const [showRoute, setShowRoute] = useState(false)

  const vm = toTripViewModel(party, myId)
  const members = party.members || []
  const order = [...members].sort((a, b) => a.rideOrder - b.rideOrder)
  const mine = members.find((m) => m.requestId === party.myRequestId)
  const waiting = ['PREPAYING', 'READY'].includes(party.status)
  const free = party.freeCancelUntil && new Date(now) <= new Date(party.freeCancelUntil)
  const postpaid = isPostpaidParty(party)
  // 후불 정산 v3는 선결제가 없다.
  const owesPrepay = party.status === 'PREPAYING' && !party.payer && !postpaid
  const canArrive = waiting && !owesPrepay && (party.instant ? !mine?.arrived : party.canArrive)
  const calling = party.payer && waiting && party.canCall && !party.calledAt
  const [people] = usePeople(party.id, party.groupVersion)
  const personOf = (memberId) => people.find((p) => p.memberId === memberId)

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
              {party.instant ? '예상 출발 ' : ''}
              {formatTime(party.departureAt)} · {meetPlace(party)}
            </h1>
          </div>
          <PartyStepper party={party} />
        </header>

        <div className="trip-grid">
          <div className="trip-main">
            <section className="home-card" aria-label="이동 및 일행 정보">
              <div className="home-card-head">
                <h2 className="section-title">같이 타는 사람</h2>
                <span className="note">하차 순서</span>
              </div>
              <Rail
                items={order.map((m) => ({
                  key: m.requestId,
                  pin: m.rideOrder,
                  avatar: <Avatar name={m.nickname} />,
                  name: m.memberId === myId ? `${m.nickname} (나)` : m.nickname,
                  badge: m.memberId === party.payerId ? '결제자' : '',
                  detail: [m.pointName, m.expectedShare != null ? won(m.expectedShare) : ''].filter(Boolean).join(', '),
                  sub: m.memberId === myId ? '' : trustLine(personOf(m.memberId)),
                  me: m.memberId === myId,
                  state: party.status === 'PREPAYING' && m.memberId !== party.payerId && typeof m.prepaid === 'boolean'
                    ? (m.prepaid ? '결제 완료' : '결제 전')
                    : waiting ? (m.noShow ? '불참' : m.arrived ? '도착' : m.nearby ? '2분 안' : '오는 중') : '',
                  ok: party.status === 'PREPAYING' ? m.prepaid === true : m.arrived,
                }))}
              />
              {party.payer && (
                <div className="trip-tool-row">
                  <Btn className="line small" onClick={() => setDriver(true)}>기사님께 보여 줄 화면</Btn>
                </div>
              )}
            </section>

            <section className="home-card" aria-label="금액 및 경로 정보">
              <h2 className="section-title">금액 구성</h2>
              <Rows
                items={[
                  ['모이는 곳', meetPlace(party)],
                  [party.instant ? '예상 출발' : '출발 시각', formatDateTime(party.departureAt)],
                  ['결제자', party.payerNickname],
                  party.freeCancelUntil && ['무료 취소 마감', formatDateTime(party.freeCancelUntil)],
                ]}
              />
              <PartyFareRows party={party} />
              {party.mySoloFare > party.myShare && <FareCompare solo={party.mySoloFare} share={party.myShare} />}
              {mine && party.mySoloFare > 0 && (
                <FairnessNote
                  delay={figures(mine).delay}
                  solo={party.mySoloFare}
                  share={party.myShare}
                  rules={config?.rules}
                  walkExcluded={isPaymentParty(party)}
                  postpaid={postpaid}
                />
              )}
              <div className="trip-tool-row">
                <Btn className="line small" onClick={() => setShowRoute((v) => !v)}>
                  <Map size={15} aria-hidden="true" />
                  {showRoute ? '경로 비교 접기' : '경로 비교 보기'}
                </Btn>
                {onDetail && <Btn className="quiet small" onClick={onDetail}>전체 경로 페이지</Btn>}
              </div>
              {showRoute && mine && <RouteComparison party={party} mine={mine} />}
            </section>

            <section className="home-card" aria-label="탑승 확인 및 규칙">
              <BoardingPanel party={party} now={now} />
              <Fold title="이용 규칙 자세히" className="rules">
                <ul>
                  <li>{postpaid
                    ? (free ? '무료 취소 마감 전 취소하면 낼 돈이 없어요.' : '무료 취소 마감이 지나 취소하면 남은 일행의 늘어난 금액을 보전 분담금으로 내요.')
                    : free ? '무료 취소 마감 전 취소하면 승인된 결제를 전액 환불해요.' : '무료 취소 마감이 지나 취소하면 남은 일행의 늘어난 금액을 보전해요.'}</li>
                  {postpaid && <li>하차 뒤 실제 요금을 구간 비율로 나누고, 동승자는 6시간 안에 금액을 확인하고 결제하거나 이의를 낼 수 있어요.</li>}
                  <li>번호 미입력만으로 노쇼를 확정하지 않아요. 24시간 소명과 관리자 심사를 거쳐 판단 불가는 노쇼 면책해요.</li>
                  <li>택시는 결제자가 직접 호출해요. 앱은 호출·탑승·정산 기록을 관리해요.</li>
                  <li>{CHAT_RETENTION}</li>
                </ul>
              </Fold>
              {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
              <PartySecondary party={party} onCancel={() => setAsking(true)} />
            </section>
          </div>

          <aside className="trip-aside">
            <section className="home-card trip-action-card" aria-label="지금 해야 할 일">
              <NextAction
                party={party}
                now={now}
                myId={myId}
                mine={mine}
                config={config}
                run={run}
                onUpdated={onUpdated}
                onDriver={() => setDriver(true)}
              />

              {['READY', 'RIDE_UNCONFIRMED', 'AWAITING_FARE'].includes(party.status) && (
                <BoardingCodePanel party={party} now={now} onUpdated={onUpdated} demo={!!config?.demo} />
              )}

              {canArrive && (
                <Btn
                  className={calling ? 'line' : undefined}
                  onClick={() => run(() => (party.instant ? api.prepareParty(party.id, 'ARRIVED') : api.arrive(party.id)), '도착을 표시했어요')}
                >
                  도착했어요
                </Btn>
              )}

              {waiting && party.instant && !mine?.arrived && !owesPrepay && (
                <Btn
                  className="line"
                  disabled={mine?.nearby}
                  onClick={() => run(() => api.prepareParty(party.id, 'NEARBY'), '2분 안 도착으로 표시했어요')}
                >
                  {mine?.nearby ? '2분 안 도착 표시 완료' : '2분 안 도착'}
                </Btn>
              )}

              {party.canLeaveAfterChange && (
                <Say
                  tone="stop-tone"
                  title="일행이 바뀌었어요"
                  action={<Btn className="small line" onClick={() => setLeavingChange(true)}>변경돼서 빠지기</Btn>}
                >
                  현재 내 분담금은 {won(party.myShare)}이에요. 원하지 않으면 {party.changeExitFree ? '호출 전까지 무료로' : ''} 빠질 수 있어요.
                </Say>
              )}
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
              <p className="note">만나는 위치·옷차림·도착 안내를 일행과 나눌 수 있어요. 결제와 탑승 인증은 이 화면에서 바로 완료할 수 있어요.</p>
              <Btn className="line" onClick={() => onMessages?.(party.id)}>
                <MessageCircle size={17} aria-hidden="true" />
                일행 메시지 열기
              </Btn>
            </section>

            {party.adminReviewRequired && (
              <section className="home-card">
                <div className="trip-alert-row">
                  <ShieldAlert size={18} aria-hidden="true" />
                  <span>관리자 검토가 진행 중인 일행이에요.</span>
                </div>
              </section>
            )}
          </aside>
        </div>
      </div>

      {leavingChange && (
        <Sheet title="변경된 일행에서 빠질까요?" onClose={() => setLeavingChange(false)}>
          <Say title={party.changeExitFree ? '무료 이탈' : '무료 아님'}>
            {party.changeExitFree ? (postpaid ? '호출 시각 전이라 낼 돈 없이 빠질 수 있어요.' : '호출 시각 전이라 승인된 결제를 전액 환불해요.') : '호출 시각이 지나 무료가 아니에요.'}
          </Say>
          <Btn
            onClick={async () => {
              await run(() => api.leaveAfterChange(party.id, party.groupVersion), (r) => r.message)
              setLeavingChange(false)
            }}
          >
            변경 이탈 확인
          </Btn>
        </Sheet>
      )}
      {asking && <CancelSheet party={party} now={now} run={run} onClose={() => setAsking(false)} />}
      {driver && <DriverView party={party} onClose={() => setDriver(false)} />}
    </Screen>
  )
}
