import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { PartyChat } from './PartyScreens.jsx'
import { SettledFeedback } from './MemberSafety.jsx'
import { formatTime, routeLabel, savedTotal, won } from './format.js'
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

export default function Home({ state, myId, onStart, onReopen, onHistory, onMenu }) {
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
          {(state.transferRestricted || (blocked && !state.restriction)) && <Say tone="stop-tone" title="보내지 않은 송금이 있어요">24시간이 지나 새 요청을 할 수 없습니다. 보낸 뒤 보냈어요를 눌러 주세요.</Say>}
          <div className="list">
            {state.parties.map((party) => (
              <Item key={party.id} title={party.settlement || party.canSettle ? '진행 중인 정산' : '진행 중인 일행'}
                detail={routeLabel({ direction: party.direction, anchorName: party.anchorName, pointName: party.meetingPoint })}
                onClick={() => onReopen(party.id)} />
            ))}
          </div>
        </div>
      )}
      {state.settledParty && <PartyChat key={state.settledParty.id} partyId={state.settledParty.id} now={state.now} status={state.settledParty.status} />}
      {state.settledParty && <SettledFeedback key={state.settledParty.id} party={state.settledParty} myId={myId} />}
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
