import React, { useEffect, useState } from 'react'
import MapView from './MapView.jsx'
import SketchMap from './SketchMap.jsx'
import { api } from './api.js'
import { formatTime, isPaymentParty, won } from './format.js'
import { Paper, Rows, Say, SidePlain } from './ui.jsx'

const CARD_WIDTH = 232
const LEGEND = '검은 실선은 같이 가는 경로, 파란 점선은 혼자 가는 경로입니다.'

/** 같이 가는 경로와 혼자 가는 경로를 함께 받는다. 일행이 바뀌면 다시 읽는다. */
function useRoutes(party) {
  const [routes, setRoutes] = useState(null)
  const [failed, setFailed] = useState(false)
  const itinerary = party.members.map((m) => m.requestId).join(',')
  useEffect(() => {
    let closed = false
    setFailed(false)
    api.partyRoutes(party.id)
      .then((data) => {
        if (closed) return
        if (data.shared?.length && data.solo?.length) setRoutes(data)
        else setFailed(true)
      })
      .catch(() => !closed && setFailed(true))
    return () => {
      closed = true
    }
  }, [party.id, itinerary])
  return { routes, failed }
}

const toLines = (routes) => [
  { path: routes.shared, style: 'solid', color: '#161a23' },
  { path: routes.solo, style: 'shortdash', color: '#1f6feb' },
]

/** 학교나 거점, 내 지점, 경로 끝의 일행 지점을 표시한다. 좌표는 경로에 있는 값만 쓴다. */
function stopsOf(party, mine, routes) {
  const toSchool = party.direction === 'TO_SCHOOL'
  const { solo, shared } = routes
  const anchor = toSchool ? solo[solo.length - 1] : shared[0]
  const me = toSchool ? solo[0] : solo[solo.length - 1]
  const far = toSchool ? shared[0] : shared[shared.length - 1]
  const farOrder = toSchool ? 1 : party.members.length
  const stops = [
    { ...anchor, label: toSchool ? '학' : '출', tone: 'end' },
    { ...me, label: mine.rideOrder, tone: 'me' },
  ]
  if (mine.rideOrder !== farOrder) stops.push({ ...far, label: farOrder })
  return stops
}

function figures(mine) {
  const solo = Math.ceil(mine.soloSeconds / 60)
  const shared = Math.ceil(mine.sharedSeconds / 60)
  return {
    solo,
    shared,
    delay: Math.max(0, shared - solo),
    saved: mine.soloFare - mine.expectedShare,
  }
}

function FullRows({ party, mine }) {
  const f = figures(mine)
  return (
    <Rows items={[
      ['혼자 타면', `${f.solo}분, ${won(mine.soloFare)}`],
      ['같이 타면', `${f.shared}분, ${won(mine.expectedShare)}`, 'big'],
      ['아끼는 돈', won(f.saved), 'save'],
      [isPaymentParty(party) ? '차량 이동 시간 차이' : '늘어나는 시간', `${f.delay}분`],
      ['도착 예정', formatTime(party.arrivalAt)],
    ]} />
  )
}

export default function RouteComparison({ party, mine }) {
  const { routes, failed } = useRoutes(party)
  return (
    <div className="block">
      <h2 className="block-title">경로 비교</h2>
      <FullRows party={party} mine={mine} />
      {isPaymentParty(party) && <p className="note">차량 이동 시간만 비교한 값이에요. 걷는 시간은 포함하지 않아요.</p>}
      {failed
        ? <p className="note">지도를 불러오지 못했습니다. 시간과 금액을 확인해 주세요.</p>
        : <p className="note">{LEGEND}</p>}
      {!failed && routes && (
        <div className="map"><MapView lines={toLines(routes)} points={stopsOf(party, mine, routes)} edge={32}
          fallback={<SketchMap lines={toLines(routes)} points={stopsOf(party, mine, routes)} label="같이 가는 경로 약도" />} /></div>
      )}
    </div>
  )
}

/** 휴대폰 일행 화면의 작은 지도. 모이는 곳(출)·내 하차 지점·마지막 하차 지점과 경로를 보여 준다. 지도 키가 없으면 약도로 그린다. */
export function PartyMiniMap({ party, mine }) {
  const { routes, failed } = useRoutes(party)
  if (failed || !routes || !mine) return null
  const lines = toLines(routes)
  const points = stopsOf(party, mine, routes)
  return (
    <figure className="mini-map">
      <MapView lines={lines} points={points} edge={28} fallback={<SketchMap lines={lines} points={points} label="같이 가는 경로 약도" />} />
      <figcaption><span><i className="both" aria-hidden="true" />같이 가는 길</span><span><i className="alone" aria-hidden="true" />혼자 갈 때</span></figcaption>
    </figure>
  )
}

/** 넓은 화면의 오른쪽 패널. 지도를 못 그리면 같은 값을 표로 보여 준다. */
export function RouteSide({ party, mine }) {
  const { routes, failed } = useRoutes(party)
  if (failed) {
    return (
      <SidePlain>
        <Paper>
          <Say tone="calm" title="지도를 불러오지 못했습니다">시간과 금액을 확인해 주세요.</Say>
          <FullRows party={party} mine={mine} />
        </Paper>
      </SidePlain>
    )
  }
  const f = figures(mine)
  return (
    <>
      {routes && <MapView lines={toLines(routes)} points={stopsOf(party, mine, routes)} right={CARD_WIDTH}
        fallback={<div className="side-sketch"><SketchMap lines={toLines(routes)} points={stopsOf(party, mine, routes)} label="같이 가는 경로 약도" /></div>} />}
      <div className="map-legend">
        <span><i className="both" aria-hidden="true" />같이 가는 경로</span>
        <span><i className="alone" aria-hidden="true" />혼자 가는 경로</span>
      </div>
      <div className="figures">
        <div><small>혼자 타면</small><b>{won(mine.soloFare)}</b></div>
        <div><small>같이 타면</small><b>{won(mine.expectedShare)}</b></div>
        <div><small>아끼는 돈</small><b className="save">{won(f.saved)}</b></div>
        <div><small>{isPaymentParty(party) ? '차량 이동 시간 차이' : '늘어나는 시간'}</small><b>{f.delay}분</b><small>{isPaymentParty(party) ? '걷는 시간 제외' : '기준 15분 이내'}</small></div>
      </div>
    </>
  )
}
