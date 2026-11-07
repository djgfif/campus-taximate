import React from 'react'
import { api } from './api.js'
import { formatDateTime, formatTime, routeLabel, won } from './format.js'
import { Hero, Rows, Screen } from './ui.jsx'

const timeRow = (latest) => (latest.direction === 'TO_SCHOOL'
  ? ['도착 희망', formatDateTime(latest.arriveBy)]
  : ['출발 가능', `${formatTime(latest.windowStart)} ~ ${formatTime(latest.windowEnd)}`])

export function Waiting({ latest, config, run }) {
  return (
    <Screen actions={<button className="secondary" onClick={() => run(() => api.cancelRequest(latest.id))}>요청 취소</button>}>
      <p className="muted">요청 완료</p>
      <h1 className="title">같은 방향 학생을<br />찾고 있어요</h1>
      <Hero label="결과는 늦어도" value={`${formatTime(latest.resultBy)}까지`} detail="3명이 모이면 더 빨리 알려 드려요" />
      <Rows items={[
        ['경로', routeLabel(latest)],
        timeRow(latest),
        ['단독 요금', `약 ${won(latest.soloFare)}`],
      ]} />
      <p className="muted">{config?.mailEnabled ? '화면을 닫아도 됩니다. 일행이 정해지면 메일로 알려 드려요.' : '결과는 이 화면에 표시됩니다. 다시 열어 확인해 주세요.'}</p>
    </Screen>
  )
}

export function Unmatched({ latest, onRetry, onHome }) {
  return (
    <Screen actions={(
      <>
        <button onClick={onRetry}>같은 경로로 다시 요청</button>
        <button className="secondary" onClick={onHome}>홈으로</button>
      </>
    )}>
      <h1 className="title">이번엔 같이 탈<br />일행을 못 찾았어요</h1>
      <p className="muted">{formatTime(latest.resultBy)}까지 조건이 맞는 학생이 없었습니다. 낸 돈은 없습니다.</p>
      <Rows title="지금 할 수 있는 것" items={[
        ['다시 요청', latest.direction === 'TO_SCHOOL' ? '시각을 바꿔 다시 찾기' : '출발 가능 시간을 넓혀 찾기'],
        ['단독으로 타기', `약 ${won(latest.soloFare)}`],
      ]} />
      <Rows items={[['지난 요청', routeLabel(latest)], timeRow(latest)]} />
    </Screen>
  )
}
