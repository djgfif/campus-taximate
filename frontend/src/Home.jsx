import React, { useEffect, useState } from 'react'
import { ChevronRight, Clock, MapPin } from 'lucide-react'
import { api } from './api.js'
import { SettledFeedback } from './MemberSafety.jsx'
import { BoardSection } from './Board.jsx'
import MapView from './MapView.jsx'
import { formatTime, partyLabel, routeLabel, savedTotal, won } from './format.js'
import { Say, Screen, TopBar, useWide } from './ui.jsx'

/** 최근 요청을 방향과 지점별로 하나씩 남긴다. */
function recentRoutes(history) {
  const seen = new Set()
  return history.filter((item) => {
    const key = `${item.direction}:${item.pointName}`
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

export default function Home({ state, myId, config, onStart, onReopen, onHistory, onMenu, onChanged }) {
  const [history, setHistory] = useState([])
  const [school, setSchool] = useState(null)
  const [board, setBoard] = useState(null)
  const [direction, setDirection] = useState(() => defaultDirection(state.now))
  const wide = useWide()
  useEffect(() => {
    api.history().then(setHistory).catch(() => setHistory([]))
    api.school().then(setSchool).catch(() => setSchool(null))
  }, [])
  const toSchool = direction === 'TO_SCHOOL'
  const recent = recentRoutes(history).filter((item) => item.direction === direction).slice(0, 3)
  const saved = savedTotal(history)
  const blocked = state.restricted
  const map = <HomeMap school={school} board={board} direction={direction} />

  return (
    <Screen aside={map}>
      {!wide && <div className="home-map">{map}<TopBar onMenu={onMenu} floating /></div>}
      <div className="home-sheet">
        {state.parties.length > 0 && (
          <ul className="live-rides" aria-label="진행 중">
            {state.parties.map((party) => (
              <li key={party.id}>
                <button type="button" className="live-ride" onClick={() => onReopen(party.id)}>
                  <span className="lr-dot" aria-hidden="true" />
                  <span className="lr-main">
                    <b>{party.settlement || party.canSettle ? '정산 진행 중' : partyLabel(party.status)}
                      {party.unreadChat > 0 && <span className="unread-badge" aria-label={`안 읽은 메시지 ${party.unreadChat}개`}>{party.unreadChat > 99 ? '99+' : party.unreadChat}</span>}</b>
                    <small>{party.departureAt ? `${formatTime(party.departureAt)} 출발, ` : ''}{routeLabel({ direction: party.direction, anchorName: party.anchorName, pointName: party.meetingPoint })}</small>
                  </span>
                  <ChevronRight size={20} aria-hidden="true" />
                </button>
              </li>
            ))}
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
                <button type="button" onClick={() => onStart({ direction: item.direction, initial: item })}>
                  <Clock size={18} aria-hidden="true" />
                  <span><b>{item.pointName}</b><small>{toSchool ? `${item.anchorName}까지` : `${item.anchorName}에서`}</small></span>
                </button>
              </li>
            ))}
          </ul>
        )}

        {state.settledParty && <button type="button" className="text-row" onClick={() => onReopen(state.settledParty.id)}>최근 정산 내역 보기<ChevronRight size={18} aria-hidden="true" /></button>}
        {state.settledParty && <SettledFeedback key={`feedback-${state.settledParty.id}`} party={state.settledParty} myId={myId} />}

        <BoardSection now={state.now} config={config} onJoined={onChanged} disabled={blocked} onLoaded={setBoard} direction={direction} />

        {!wide && (
          <button type="button" className="text-row" onClick={onHistory}>
            이용 내역{saved > 0 && <span className="text-row-value">{won(saved)} 아꼈어요</span>}<ChevronRight size={18} aria-hidden="true" />
          </button>
        )}
      </div>
    </Screen>
  )
}
