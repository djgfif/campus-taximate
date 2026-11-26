import React, { useEffect, useState } from 'react'
import { ChevronRight, Clock, MapPin } from 'lucide-react'
import { api } from './api.js'
import { SettledFeedback } from './MemberSafety.jsx'
import { BoardSection } from './Board.jsx'
import { defaultDirection, formatTime, isPostpaidParty, partyLabel, partyRouteLabel, remaining } from './format.js'
import { Say, Screen, TopBar, useWide } from './ui.jsx'

/**
 * 최근 요청을 방향과 지점별로 하나씩 남긴다. 등교/하교 구분은 서버가 정한 displayDirection을 그대로 쓴다
 * (공통 승차로 학교에 가는 요청도 등교). 규칙은 서버(BoardService.displayDirection) 한 곳에만 둔다.
 */
function recentRoutes(history) {
  const seen = new Set()
  return history.map((item) => ({ ...item, displayDirection: item.displayDirection || item.direction })).filter((item) => {
    const key = `${item.displayDirection}:${item.pointName}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** 홈 상단 진행 카드의 제목. 서버 상태 이름 대신 '내가 지금 할 일'을 보여 준다. */
function liveTitle(party, myId) {
  if (party.canSettle) return '실제 택시비를 입력해 주세요'
  if (party.settlement) {
    if (isPostpaidParty(party)) {
      if (!party.payer && !party.settlement.myConfirmed) {
        return party.settlement.payable ? '정산 금액을 확인하고 결제해 주세요' : '정산 검토 결과를 기다려요'
      }
      if (party.payer) return '동승자 확인·결제 현황 확인'
    }
    return '정산하고 있어요'
  }
  if (party.status === 'PAYER_PENDING') {
    if (party.successorId !== myId) return '새 결제자를 정하고 있어요'
    return party.payerNickname ? `결제자 ${party.payerNickname}님이 아직 안 왔어요` : '결제자가 아직 안 왔어요'
  }
  if (party.status === 'PREPAYING') {
    if (!party.payer && !party.myPrepaid) return '선결제를 해 주세요'
    return '다른 사람들이 결제하는 중이에요'
  }
  if (party.status === 'READY') return party.payer && party.canCall ? '이제 택시를 불러 주세요' : '승차 지점에 모이는 중이에요'
  if (party.status === 'RIDE_UNCONFIRMED' || party.canConfirmRide) return '택시에 탔는지 알려 주세요'
  return partyLabel(party.status)
}

/** 답할 마감이 있으면 남은 시간을 붙인다. */
function liveDue(party, now) {
  if (party.status === 'PAYER_PENDING' && party.successionDeadlineAt) {
    return `답할 마감까지 ${remaining(party.successionDeadlineAt, now)}`
  }
  if (party.status === 'PREPAYING' && !party.payer && !party.myPrepaid && party.prepayDeadlineAt) {
    return `선결제 마감까지 ${remaining(party.prepayDeadlineAt, now)}`
  }
  if (party.settlement && isPostpaidParty(party) && !party.payer && !party.settlement.myConfirmed) {
    const due = party.settlement.autoFinalizeAt || party.settlement.objectionDeadlineAt
    if (due) return `확인 마감까지 ${remaining(due, now)}`
  }
  return ''
}

/**
 * 지도 없는 요청 중심 소비자 홈.
 * 홈에서는 지도 SDK를 마운트하지 않으며, 새 동승 요청과 진행 중인 이용을 최우선으로 배치한다.
 */
export default function Home({ state, myId, config, school, onStart, onReopen, onChanged, onFind, tabBar }) {
  const [history, setHistory] = useState([])
  const [direction, setDirection] = useState(() => defaultDirection(state.now))
  const wide = useWide()

  useEffect(() => {
    let closed = false
    api.history().then((items) => !closed && setHistory(items)).catch(() => !closed && setHistory([]))
    return () => { closed = true }
  }, [])

  const toSchool = direction === 'TO_SCHOOL'
  const recent = recentRoutes(history).filter((item) => item.displayDirection === direction).slice(0, 3)
  const blocked = state.restricted
  const schoolName = school?.name
  const maxDelay = config?.rules?.maxDelayMinutes ?? 15
  const minSaving = Math.round((config?.rules?.minSavingRate ?? 0.1) * 100)

  return (
    <Screen tabBar={tabBar}>
      {!wide && <TopBar />}
      <div className="home-page">
        <section className="home-hero" aria-label="동승 요청 시작">
          <div className="home-hero-head">
            <h1 className="home-title">오늘 어디로 가나요?</h1>
            {schoolName && <p className="home-lead">{schoolName} 학생과 같은 방향 택시비를 나눠 내요.</p>}
          </div>

          <div className="dir-tabs" role="tablist" aria-label="방향">
            <button type="button" role="tab" aria-selected={toSchool} className={toSchool ? 'dir-tab on' : 'dir-tab'} onClick={() => setDirection('TO_SCHOOL')}>등교</button>
            <button type="button" role="tab" aria-selected={!toSchool} className={!toSchool ? 'dir-tab on' : 'dir-tab'} onClick={() => setDirection('FROM_HUB')}>하교</button>
          </div>

          <button type="button" className="home-search-btn" disabled={blocked} onClick={() => onStart({ direction })}>
            <span className="home-search-left">
              <MapPin size={18} aria-hidden="true" />
              <span>{toSchool ? '어디서 출발하세요?' : '어디로 가세요?'}</span>
            </span>
            <ChevronRight className="home-search-arrow" size={18} aria-hidden="true" />
          </button>

          {!blocked && recent.length > 0 && (
            <div className="home-recent-wrap">
              <ul className="home-recent-list" aria-label="최근 경로">
                {recent.map((item) => (
                  <li key={item.id}>
                    <button type="button" className="home-recent-row" onClick={() => onStart({ direction: item.displayDirection, initial: item })}>
                      <Clock size={15} aria-hidden="true" />
                      <span className="home-recent-route">
                        {item.direction === 'TO_SCHOOL' ? `${item.pointName} → ${item.anchorName}` : `${item.anchorName} → ${item.pointName}`}
                      </span>
                      <ChevronRight size={15} aria-hidden="true" />
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>

        {state.restriction && <Say tone="stop-tone" title="신고 누적으로 이용이 제한됐어요">{state.restriction.message}</Say>}
        {(state.transferRestricted || (blocked && !state.restriction)) && (
          <Say tone="stop-tone" title="미납 내역이 있어요">결제 또는 이전 미정산 내역을 확인해 주세요. 해결하면 새 요청을 할 수 있어요.</Say>
        )}

        {state.parties.length > 0 && (
          <section className="home-section" aria-label="진행 중인 이용">
            <div className="home-section-head">
              <h2 className="section-title">진행 중인 이용</h2>
              <span className="section-count">{state.parties.length}</span>
            </div>
            <div className="surface-box">
              <ul className="live-rides" aria-label="내 일행">
                {state.parties.map((party) => {
                  const due = liveDue(party, state.now)
                  return (
                    <li key={party.id}>
                      <button type="button" className="live-ride" onClick={() => onReopen(party.id)}>
                        <span className="lr-main">
                          <small>내 일행{party.departureAt ? ` · ${formatTime(party.departureAt)} 출발` : ''}</small>
                          <b>
                            {liveTitle(party, myId)}
                            {party.unreadChat > 0 && (
                              <span className="unread-badge" aria-label={`안 읽은 메시지 ${party.unreadChat}개`}>
                                {party.unreadChat > 99 ? '99+' : party.unreadChat}
                              </span>
                            )}
                          </b>
                          <small>{due || partyRouteLabel(party)}</small>
                        </span>
                        <ChevronRight size={18} aria-hidden="true" />
                      </button>
                    </li>
                  )
                })}
              </ul>
            </div>
          </section>
        )}

        <div className="home-grid">
          <div className="home-primary">
            {state.settledParty && (
              <section className="surface-box surface-pad">
                <button type="button" className="text-row" onClick={() => onReopen(state.settledParty.id)}>
                  최근 정산 내역 보기
                  <ChevronRight size={18} aria-hidden="true" />
                </button>
                <SettledFeedback key={`feedback-${state.settledParty.id}`} party={state.settledParty} myId={myId} />
              </section>
            )}

            {state.parties.length === 0 && !state.settledParty && (
              <section className="home-section" aria-label="이용 방법">
                <h2 className="section-title">이용 방법</h2>
                <div className="surface-box">
                  <ol className="home-steps">
                    <li>
                      <span className="home-step-num" aria-hidden="true">01</span>
                      <div>
                        <strong>이동 조건 입력</strong>
                        <p>출발지와 목적지, 출발 가능 시간을 한 번만 선택해요.</p>
                      </div>
                    </li>
                    <li>
                      <span className="home-step-num" aria-hidden="true">02</span>
                      <div>
                        <strong>자동 매칭 및 비율 분담</strong>
                        <p>우회 {maxDelay}분 이하, 혼자보다 {minSaving}% 이상 절약될 때만 연결돼요.</p>
                      </div>
                    </li>
                    <li>
                      <span className="home-step-num" aria-hidden="true">03</span>
                      <div>
                        <strong>탑승 확인 및 정산</strong>
                        <p>6자리 번호로 함께 탄 뒤, 영수증 기준으로 정확히 나눠요.</p>
                      </div>
                    </li>
                  </ol>
                </div>
              </section>
            )}
          </div>

          <aside className="home-secondary">
            <section className="surface-box surface-pad">
              <BoardSection
                now={state.now}
                config={config}
                direction={direction}
                onSwitch={() => setDirection(toSchool ? 'FROM_HUB' : 'TO_SCHOOL')}
                onJoined={onChanged}
                disabled={blocked}
                limit={3}
              />
              {onFind && (
                <button type="button" className="text-row home-more-link" onClick={onFind}>
                  동승 찾기에서 더 보기
                  <ChevronRight size={16} aria-hidden="true" />
                </button>
              )}
            </section>
          </aside>
        </div>
      </div>
    </Screen>
  )
}
