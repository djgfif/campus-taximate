import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { SettledFeedback } from './MemberSafety.jsx'
import { BoardSection } from './Board.jsx'
import { formatTime, partyLabel, routeLabel, savedTotal, won } from './format.js'
import { Item, Paper, Say, Screen, SideEmpty, SidePlain, TopBar, useWide } from './ui.jsx'

/** 최근 요청을 방향과 지점별로 하나씩 남긴다. */
function recentRoutes(history) {
  const seen = new Set()
  return history.filter((item) => {
    const key = `${item.direction}:${item.pointName}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  }).slice(0, 3)
}

export default function Home({ state, myId, config, onStart, onReopen, onHistory, onMenu, onChanged }) {
  const [history, setHistory] = useState([])
  const wide = useWide()
  useEffect(() => {
    api.history().then(setHistory).catch(() => setHistory([]))
  }, [])
  const recent = recentRoutes(history)
  const saved = savedTotal(history)
  const routes = recent.map((item) => (
    <Item key={item.id} title={routeLabel(item)}
      detail={`지난번 ${formatTime(item.arriveBy || item.windowStart)}`}
      onClick={() => onStart({ direction: item.direction, initial: item })} />
  ))
  const blocked = state.restricted
  const aside = recent.length === 0
    ? <SideEmpty title="첫 요청을 만들어 보세요">이용한 경로와 아낀 금액이 여기에 쌓입니다.</SideEmpty>
    : (
      <SidePlain>
        {saved > 0 && <Paper><span className="k">지금까지 아낀 금액</span><strong className="saved">{won(saved)}</strong></Paper>}
        {!blocked && <Paper title="최근 경로"><div className="list">{routes}</div></Paper>}
      </SidePlain>
    )
  return (
    <Screen aside={aside}>
      <TopBar onMenu={onMenu} />
      <div className="home-head">
        <h1 className="head">어디로 가세요?</h1>
        <p className="sub">같은 방향 학생과 택시비를 나눠요</p>
      </div>
      {(blocked || state.parties.length > 0) && (
        <div className="pad">
          {state.restriction && <Say tone="stop-tone" title="신고 누적으로 이용이 제한됐어요">{state.restriction.message}</Say>}
          {(state.transferRestricted || (blocked && !state.restriction)) && <Say tone="stop-tone" title="미납 내역이 있어요">결제 또는 이전 미정산 내역을 확인해 주세요. 해결하면 새 요청을 할 수 있어요.</Say>}
          <div className="list">
            {state.parties.map((party) => (
              <Item key={party.id} title={<>{party.settlement || party.canSettle ? '진행 중인 정산' : '진행 중인 일행'}
                {party.unreadChat > 0 && <span className="unread-badge" aria-label={`안 읽은 메시지 ${party.unreadChat}개`}>{party.unreadChat > 99 ? '99+' : party.unreadChat}</span>}</>}
                detail={`${party.departureAt ? formatTime(party.departureAt) + ' 출발 · ' : ''}${partyLabel(party.status)} · ${routeLabel({ direction: party.direction, anchorName: party.anchorName, pointName: party.meetingPoint })}`}
                onClick={() => onReopen(party.id)} />
            ))}
          </div>
        </div>
      )}
      {state.settledParty && <div className="block"><Item title="최근 정산 내역 보기" onClick={() => onReopen(state.settledParty.id)} /></div>}
      {state.settledParty && <SettledFeedback key={`feedback-${state.settledParty.id}`} party={state.settledParty} myId={myId} />}
      <div className="ways">
        <button type="button" className="way" disabled={blocked} onClick={() => onStart({ direction: 'TO_SCHOOL' })}>
          <b>등교</b>
          <span className="trip">집 앞<i aria-hidden="true" />학교</span>
        </button>
        <button type="button" className="way night" disabled={blocked} onClick={() => onStart({ direction: 'FROM_HUB' })}>
          <b>하교</b>
          <span className="trip">학교<i aria-hidden="true" />집 앞</span>
        </button>
      </div>
      <BoardSection now={state.now} config={config} onJoined={onChanged} disabled={blocked} />
      {recent.length === 0 && state.parties.length === 0 && !blocked && (
        <section className="how" aria-label="이용 방법">
          <h2 className="block-title">처음이라면</h2>
          <ol>
            <li><b>가는 길을 올리면</b><span>같은 방향 학생을 찾아 줘요</span></li>
            <li><b>일행이 모이면 미리 결제</b><span>택시비를 떼일 걱정이 없어요</span></li>
            <li><b>같이 타고 내리면 끝</b><span>타고 나서 남는 돈은 돌려받아요</span></li>
          </ol>
        </section>
      )}
      {!wide && (
        <>
          <div className="band" />
          <div className="block">
            {!blocked && recent.length > 0 && (
              <>
                <h2 className="block-title">최근 경로</h2>
                <div className="list">{routes}</div>
              </>
            )}
            <div className="list"><Item title="이용 내역" onClick={onHistory} /></div>
          </div>
        </>
      )}
    </Screen>
  )
}
