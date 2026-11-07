import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { formatTime, routeLabel } from './format.js'
import { MoreLink, Screen } from './ui.jsx'

/** 최근 요청을 방향과 지점별로 하나씩 남긴다. */
function recentRoutes(history) {
  const seen = new Set()
  return history.filter((item) => {
    const key = `${item.direction}:${item.pointName}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  }).slice(0, 2)
}

export default function Home({ state, onStart, onReopen, onHistory }) {
  const [recent, setRecent] = useState([])
  useEffect(() => {
    api.history().then((list) => setRecent(recentRoutes(list))).catch(() => setRecent([]))
  }, [])
  const blocked = state.restricted
  const pending = state.parties
  return (
    <Screen>
      <h1 className="title">어디로 가세요?</h1>
      <p className="muted">같은 방향 학생을 자동으로 찾고, 각자 단독 요금 비율로 택시비를 나눕니다.</p>
      {blocked && <p className="error">24시간이 지나도 보내지 않은 송금이 있어 새 요청을 할 수 없습니다. 보낸 뒤 보냈어요를 눌러 주세요.</p>}
      {pending.map((party) => (
        <MoreLink key={party.id} label={party.settlement || party.canSettle ? '진행 중인 정산' : '진행 중인 일행'} detail="열기" onClick={() => onReopen(party.id)} />
      ))}
      <div className="pick">
        <button disabled={blocked} onClick={() => onStart({ direction: 'TO_SCHOOL' })}><b>등교</b><span>집 앞에서<br />학교까지</span></button>
        <button disabled={blocked} className="dark" onClick={() => onStart({ direction: 'FROM_HUB' })}><b>하교</b><span>학교에서<br />집 앞까지</span></button>
      </div>
      {recent.length > 0 && <p className="field">자주 쓰는 경로</p>}
      {!blocked && recent.map((item) => (
        <MoreLink key={item.id} label={routeLabel(item)}
          detail={`지난번 ${formatTime(item.arriveBy || item.windowStart)}`}
          onClick={() => onStart({ direction: item.direction, initial: item })} />
      ))}
      <button className="link" onClick={onHistory}>이용 내역</button>
    </Screen>
  )
}
