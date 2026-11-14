import React, { useEffect, useMemo, useState } from 'react'
import MapView from './MapView.jsx'
import { api } from './api.js'
import { pushUndecided } from './push.js'
import { formatDateTime, formatTime, routeLabel, won } from './format.js'
import { Btn, Confirm, Meter, Rail, Rows, Say, Screen, TopBar } from './ui.jsx'

const timeRow = (latest) => (latest.mode==='INSTANT' ? ['출발 준비','지금 출발, 확정 뒤 예상 출발 안내'] : latest.arriveBy && !latest.windowStart
  ? ['도착 희망', formatDateTime(latest.arriveBy)]
  : ['출발 가능', `${formatTime(latest.windowStart)} ~ ${formatTime(latest.windowEnd)}`])

/** 넓은 화면의 오른쪽. 요청의 내 지점과 학교나 거점을 표시한다. */
export function RequestMap({ item, edge }) {
  const toSchool = item.direction === 'TO_SCHOOL'
  return (
    <MapView edge={edge} points={[
      { lat: item.pointLat, lon: item.pointLon, label: toSchool ? '승' : '도', tone: 'me' },
      { lat: item.anchorLat, lon: item.anchorLon, label: toSchool ? '학' : '출', tone: 'end' },
    ]} />
  )
}

/** 왜 아직 일행이 없는지 말한다. 다음 확인 시각까지 함께 알려 기다릴지 정하게 한다. */
export function Situation({ insight, now }) {
  if (!insight) return null
  const next = new Date(insight.nextCheckAt) > new Date(now)
    ? `다음 확인은 ${formatDateTime(insight.nextCheckAt)}입니다.` : '지금 조건을 다시 확인하고 있어요.'
  switch (insight.stage) {
    case 'NOT_STARTED':
      return <Say tone="calm" title={`${formatTime(insight.searchStartsAt)}부터 찾기 시작해요`}>출발 가능 시간에 맞춰 일행을 찾습니다.</Say>
    case 'ALONE':
      return <Say tone="calm" title="같은 시간대에 기다리는 학생이 아직 없어요">{next} 그 사이 요청이 들어오면 함께 확인합니다.</Say>
    case 'OTHER_DIRECTION':
      return <Say tone="calm" title={`같은 시간대 ${insight.peers}명이 있지만 방향이 달라요`}>{next} 같은 방향 요청을 기다립니다.</Say>
    default:
      return <Say title={`같은 방향 ${insight.sameDirection}명과 조건을 확인하고 있어요`}>지연 15분, 요금 10% 조건이 맞으면 일행이 됩니다. {next}</Say>
  }
}

export function Waiting({ latest, config, now, run, onMenu, onPush }) {
  const [asking, setAsking] = useState(false)
  const toSchool = latest.direction === 'TO_SCHOOL'
  const anchor = useMemo(() => ({ stamp: new Date(now).getTime(), readAt: performance.now() }), [now])
  const [, tick] = useState(0)
  useEffect(() => { const timer = setInterval(() => tick((n) => n + 1), 1000); return () => clearInterval(timer) }, [])
  const current = Number.isFinite(anchor.stamp) ? anchor.stamp + performance.now() - anchor.readAt : null
  return (
    <Screen aside={<RequestMap item={latest} />} foot={<Btn className="line" onClick={() => setAsking(true)}>요청 취소</Btn>}>
      <TopBar dark onMenu={onMenu} />
      <Meter label="결과는 늦어도" value={formatTime(latest.resultBy)} detail="그 전에 일행이 정해지면 바로 알려 드려요">
        <div className="road" aria-hidden="true"><i /></div>
      </Meter>
      <div className="block">
        {latest.mode==='INSTANT'&&<Say>{config?.instantMatching?.matchMinutes??5}분 동안 같이 탈 사람을 찾아요. 확정되면 예상 출발 시각을 알려 드려요.</Say>}
        {config?.demo && <p className="note">시연 시각 {formatDateTime(current)}</p>}
        <Situation insight={latest.insight} now={current} />
        {onPush && pushUndecided() && (
          <Say tone="calm" title="화면을 닫아도 결과를 받으려면">
            알림을 켜면 일행 확정과 승차 10분 전 안내가 이 기기로 옵니다.
            <Btn className="small line push-on" onClick={onPush}>알림 켜기</Btn>
          </Say>
        )}
        <Rail items={toSchool ? [
          { key: 'me', pin: '승', name: latest.pointName, me: true },
          { key: 'end', pin: '학', name: latest.anchorName, end: true },
        ] : [
          { key: 'from', pin: '출', name: latest.anchorName, end: true },
          { key: 'me', pin: '도', name: latest.pointName, me: true },
        ]} />
        <Rows items={[timeRow(latest), ['혼자 타면', `약 ${won(latest.soloFare)}`]]} />
        <p className="note">{config?.mailEnabled ? '화면을 닫아도 됩니다. 일행이 정해지면 메일로 알려 드려요.' : '결과는 이 화면에 표시됩니다.'}</p>
      </div>
      {asking && (
        <Confirm title="요청을 취소할까요?" action="요청 취소" onClose={() => setAsking(false)}
          onConfirm={() => run(() => api.cancelRequest(latest.id), '요청을 취소했어요')}>
          확정 전이라 기록이 남지 않습니다.
        </Confirm>
      )}
    </Screen>
  )
}

/** 일행에서 빠졌다는 사실과 지금 할 수 있는 일을 알린다. */
export function NoShow({ latest, onRetry, onHome, onMenu }) {
  return (
    <Screen aside={<RequestMap item={latest} />} foot={(
      <>
        <Btn onClick={onRetry}>같은 경로로 다시 요청</Btn>
        <Btn className="quiet" onClick={onHome}>홈으로</Btn>
      </>
    )}>
      <TopBar onMenu={onMenu} />
      <div className="pad">
        <h1 className="head">이번 일행에서<br />빠졌어요</h1>
        <p className="sub">승차 시각까지 도착 표시가 없어 노쇼로 기록했습니다.</p>
        <Rows items={[['지난 요청', routeLabel(latest)], timeRow(latest)]} />
        <Say tone="calm">이번 일행의 택시비는 내지 않습니다.</Say>
      </div>
    </Screen>
  )
}

export function Unmatched({ latest, onRetry, onHome, onMenu }) {
  if (latest.endReason === 'LEFT_ALONE') {
    return (
      <Screen aside={<RequestMap item={latest} />} foot={<Btn className="line" onClick={onHome}>홈으로</Btn>}>
        <TopBar onMenu={onMenu} />
        <div className="pad">
          <h1 className="head">일행이 모두<br />빠졌어요</h1>
          <p className="sub">혼자 이동하게 되어 나눌 금액이 없습니다.</p>
          <Rows items={[['지난 요청', routeLabel(latest)], timeRow(latest)]} />
        </div>
      </Screen>
    )
  }
  const canceled = latest.endReason === 'PARTY_CANCELED'
  return (
    <Screen aside={<RequestMap item={latest} />} foot={(
      <>
        <Btn onClick={onRetry}>같은 경로로 다시 요청</Btn>
        <Btn className="quiet" onClick={onHome}>홈으로</Btn>
      </>
    )}>
      <TopBar onMenu={onMenu} />
      <div className="pad">
        <h1 className="head">{canceled ? '일행이 취소됐어요' : <>이번엔 같이 탈<br />일행을 못 찾았어요</>}</h1>
        <p className="sub">{canceled ? '다른 일행이 빠져 나만 남았습니다.' : `${formatTime(latest.resultBy)}까지 조건이 맞는 학생이 없었습니다.`}</p>
        <Rows items={[
          ['지난 요청', routeLabel(latest)],
          timeRow(latest),
          ['혼자 타면', `약 ${won(latest.soloFare)}`],
        ]} />
        <Say tone="calm">{latest.direction === 'TO_SCHOOL' ? '도착 희망 시각을 바꿔 다시 찾을 수 있어요.' : '출발 가능 시간을 넓히면 일행을 찾기 쉬워요.'}</Say>
      </div>
    </Screen>
  )
}
