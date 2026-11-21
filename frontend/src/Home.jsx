import React, { useEffect, useState } from 'react'
import { ChevronRight, Clock, MapPin } from 'lucide-react'
import { api } from './api.js'
import { SettledFeedback } from './MemberSafety.jsx'
import { BoardSection } from './Board.jsx'
import MapView from './MapView.jsx'
import { formatTime, partyLabel, remaining, routeLabel } from './format.js'
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

/** 서울 시각 오후 2시 전에는 등교, 그 뒤에는 하교를 먼저 보여 준다. */
function defaultDirection(now) {
  const hour = Number(new Date(now || Date.now()).toLocaleString('en-US', { hour: 'numeric', hour12: false, timeZone: 'Asia/Seoul' }))
  return hour < 14 ? 'TO_SCHOOL' : 'FROM_HUB'
}

/** 홈 맨 위 할 일 줄의 제목. 일행 상태가 아니라 '내가 지금 할 일'을 사람 말로 쓴다. */
function liveTitle(party, myId) {
  if (party.settlement || party.canSettle) return party.canSettle ? '실제 택시비를 입력해 주세요' : '정산하고 있어요'
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
  const due = party.status === 'PAYER_PENDING' ? party.successionDeadlineAt
    : party.status === 'PREPAYING' && !party.payer && !party.myPrepaid ? party.prepayDeadlineAt : null
  if (!due) return ''
  return party.status === 'PAYER_PENDING' ? `답할 마감까지 ${remaining(due, now)}` : `선결제 마감까지 ${remaining(due, now)}`
}

/** 학교와 모집 중인 일행의 모이는 곳을 한 지도에. */
function HomeMap({ school, board, direction }) {
  if (!school) return <div className="map-canvas" aria-hidden="true" />
  // 지도를 못 불러와도 빈 회색 판이 아니라 학교 위치만은 보이게 한다.
  const fallback = <div className="map-canvas map-fallback" aria-hidden="true"><span className="mf-pin"><i />{school.name}</span></div>
  const points = [{ lat: school.lat, lon: school.lon, label: '학', tone: 'end' }]
  for (const card of (board?.parties || []).filter((c) => c.direction === direction)) {
    if (card.anchorLat && card.anchorLon) points.push({ lat: card.anchorLat, lon: card.anchorLon, label: formatTime(card.departureAt).replace(/^오[전후] /, ''), tone: 'time' })
  }
  return <MapView points={points} edge={56} fallback={fallback} />
}

export default function Home({ state, myId, config, school: knownSchool, onStart, onReopen, onChanged, tabBar }) {
  const [history, setHistory] = useState([])
  const [loadedSchool, setLoadedSchool] = useState(null)
  const [board, setBoard] = useState(null)
  const [direction, setDirection] = useState(() => defaultDirection(state.now))
  const wide = useWide()
  // 학교는 App이 한 번 읽어 넘긴다(읽는 중이면 null). school을 아예 넘기지 않은 화면(시험 등)만 여기서 직접 읽는다.
  const ownsSchool = knownSchool === undefined
  const school = ownsSchool ? loadedSchool : knownSchool
  useEffect(() => {
    let closed = false
    api.history().then((items) => !closed && setHistory(items)).catch(() => !closed && setHistory([]))
    return () => { closed = true }
  }, [])
  useEffect(() => {
    if (!ownsSchool) return undefined
    let closed = false
    api.school().then((data) => !closed && setLoadedSchool(data)).catch(() => !closed && setLoadedSchool(null))
    return () => { closed = true }
  }, [ownsSchool])
  const toSchool = direction === 'TO_SCHOOL'
  const recent = recentRoutes(history).filter((item) => item.displayDirection === direction).slice(0, 3)
  const blocked = state.restricted
  const map = <HomeMap school={school} board={board} direction={direction} />

  return (
    <Screen aside={map}>
      {!wide && <div className="home-map">{map}<TopBar floating /></div>}
      <div className="home-sheet">
        {state.parties.length > 0 && (
          <ul className="live-rides" aria-label="내 일행">
            {state.parties.map((party) => {
              const due = liveDue(party, state.now)
              return <li key={party.id}>
                <button type="button" className="live-ride" onClick={() => onReopen(party.id)}>
                  <span className="lr-main">
                    <small>내 일행{party.departureAt ? `, ${formatTime(party.departureAt)} 출발` : ''}</small>
                    <b>{liveTitle(party, myId)}
                      {party.unreadChat > 0 && <span className="unread-badge" aria-label={`안 읽은 메시지 ${party.unreadChat}개`}>{party.unreadChat > 99 ? '99+' : party.unreadChat}</span>}</b>
                    <small>{due || routeLabel({ direction: party.direction, anchorName: party.anchorName, pointName: party.meetingPoint })}</small>
                  </span>
                  <ChevronRight size={20} aria-hidden="true" />
                </button>
              </li>
            })}
          </ul>
        )}

        {state.restriction && <Say tone="stop-tone" title="신고 누적으로 이용이 제한됐어요">{state.restriction.message}</Say>}
        {(state.transferRestricted || (blocked && !state.restriction)) && <Say tone="stop-tone" title="미납 내역이 있어요">결제 또는 이전 미정산 내역을 확인해 주세요. 해결하면 새 요청을 할 수 있어요.</Say>}

        <div className="seg" role="tablist" aria-label="방향">
          <button type="button" role="tab" aria-selected={toSchool} className={toSchool ? 'on' : ''} onClick={() => setDirection('TO_SCHOOL')}>등교</button>
          <button type="button" role="tab" aria-selected={!toSchool} className={toSchool ? '' : 'on'} onClick={() => setDirection('FROM_HUB')}>하교</button>
        </div>

        <button type="button" className="where" disabled={blocked} onClick={() => onStart({ direction })}>
          <MapPin size={20} aria-hidden="true" />
          <span>{toSchool ? '어디서 출발하세요?' : '어디로 가세요?'}</span>
        </button>

        {!blocked && recent.length > 0 && (
          <ul className="recent" aria-label="최근 경로">
            {recent.map((item) => (
              <li key={item.id}>
                <button type="button" onClick={() => onStart({ direction: item.displayDirection, initial: item })}>
                  <Clock size={18} aria-hidden="true" />
                  <span><b>{item.pointName}</b><small>{item.direction === 'TO_SCHOOL' ? `${item.anchorName}까지` : `${item.anchorName}에서`}</small></span>
                </button>
              </li>
            ))}
          </ul>
        )}

        {state.settledParty && <button type="button" className="text-row" onClick={() => onReopen(state.settledParty.id)}>최근 정산 내역 보기<ChevronRight size={18} aria-hidden="true" /></button>}
        {state.settledParty && <SettledFeedback key={`feedback-${state.settledParty.id}`} party={state.settledParty} myId={myId} />}

        <BoardSection now={state.now} config={config} onJoined={onChanged} disabled={blocked} onLoaded={setBoard} direction={direction}
          onSwitch={() => setDirection(toSchool ? 'FROM_HUB' : 'TO_SCHOOL')} />

      </div>
      {tabBar}
    </Screen>
  )
}
