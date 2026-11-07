import React, { useState } from 'react'
import { Check } from 'lucide-react'
import RouteComparison, { RouteSide } from './RouteComparison.jsx'
import { api } from './api.js'
import { formatTime, remaining, won } from './format.js'
import { BackBar, Btn, Confirm, Item, Meter, Rail, Rows, Say, Screen, TopBar, useWide } from './ui.jsx'

const REAL_NAME = '송금할 때 서로의 실명이 표시될 수 있습니다.'

/** 도착 전에는 누르는 버튼, 도착 뒤에는 표시 완료로 바뀐다. */
function Foot({ party, primary, onCancel, onHome }) {
  return (
    <>
      {primary}
      {party.canCancel && <Btn className="quiet" onClick={onCancel}>내 탑승 취소</Btn>}
      {!primary && !party.canCancel && <Btn className="line" onClick={onHome}>홈으로</Btn>}
    </>
  )
}

const Arrived = () => <button type="button" className="btn done" disabled><Check size={20} aria-hidden="true" />도착 표시 완료</button>

function CancelSheet({ party, run, onClose }) {
  return (
    <Confirm title="탑승을 취소할까요?" action="탑승 취소" onClose={onClose}
      onConfirm={() => run(() => api.cancelRequest(party.myRequestId), '탑승을 취소했어요')}>
      남은 일행의 분담금이 다시 계산됩니다. 일행이 한 명만 남으면 일행이 취소됩니다.
    </Confirm>
  )
}

const shareRows = (party) => [
  ['내 예상 분담금', won(party.myShare), 'big'],
  ['혼자 탈 때보다', `${won(party.mySoloFare - party.myShare)} 절약`, 'save'],
]

function HomeParty({ party, now, myId, run, onDetail, onHome, onMenu }) {
  const [asking, setAsking] = useState(false)
  const leaving = !party.canArrive && !party.canCancel
  const mine = party.members.find((m) => m.requestId === party.myRequestId)
  const primary = party.canArrive
    ? <Btn onClick={() => run(() => api.arrive(party.id), '도착을 표시했어요')}>도착했어요</Btn>
    : mine?.arrived && !leaving ? <Arrived /> : null
  return (
    <Screen aside={mine ? <RouteSide party={party} mine={mine} /> : null} foot={<Foot party={party} primary={primary} onCancel={() => setAsking(true)} onHome={onHome} />}>
      <TopBar dark onMenu={onMenu} />
      <Meter label="출발까지" value={remaining(party.departureAt, now)}
        detail={`${formatTime(party.departureAt)} ${party.meetingPoint}`} tag={party.payer ? '내가 결제자' : null} />
      <div className="block">
        {leaving ? (
          <Say title="이동 중이에요">
            {party.payer ? '도착해서 결제하면 금액을 입력해 주세요.' : `${party.payerNickname}님이 결제 금액을 입력하면 보낼 돈을 알려 드려요.`}
          </Say>
        ) : party.payer ? (
          <Say title="모두 모이면 택시를 호출해 주세요">마지막에 내리는 사람이 택시를 잡고 결제합니다. {REAL_NAME}</Say>
        ) : (
          <Say title={mine?.arrived ? '도착을 표시했어요' : '시간에 맞춰 정류장으로 가세요'}>택시는 {party.payerNickname}님이 잡습니다.</Say>
        )}
        <h2 className="block-title">{party.payer ? '기사님께 알려 줄 하차 순서' : '하차 순서'}</h2>
        <Rail items={party.members.map((m) => ({
          key: m.requestId,
          pin: m.rideOrder,
          name: m.memberId === myId ? `나 (${m.nickname})` : m.nickname,
          detail: m.pointName,
          me: m.memberId === myId,
          state: leaving ? '' : m.arrived ? '도착' : '오는 중',
          ok: m.arrived,
        }))} />
      </div>
      <div className="band" />
      <div className="block">
        <Rows items={shareRows(party)} />
        <div className="list"><Item title="일행과 경로 자세히" onClick={onDetail} /></div>
      </div>
      {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
    </Screen>
  )
}

function SchoolPayer({ party, now, myId, run, onDetail, onHome, onMenu }) {
  const [asking, setAsking] = useState(false)
  const boarded = party.boardedAt != null
  const handedOver = party.members.some((m) => m.noShow)
  const mine = party.members.find((m) => m.requestId === party.myRequestId)
  const callNow = new Date(party.callAt) <= new Date(now)
  const primary = party.canBoard ? <Btn onClick={() => run(() => api.boarded(party.id), '탑승을 표시했어요')}>택시 탔어요</Btn> : null
  const stops = party.members.map((m) => ({
    key: m.requestId,
    pin: m.rideOrder,
    name: m.memberId === myId ? `나 (${m.nickname})` : m.nickname,
    detail: m.noShow ? '오지 않음' : m.pointName,
    time: formatTime(m.boardAt),
    me: m.memberId === myId,
    off: m.noShow,
  }))
  stops.push({ key: 'school', pin: '학', name: `${party.anchorName} 도착`, detail: '함께 내림', time: formatTime(party.arrivalAt), end: true })
  return (
    <Screen aside={mine ? <RouteSide party={party} mine={mine} /> : null} foot={<Foot party={party} primary={primary} onCancel={() => setAsking(true)} onHome={onHome} />}>
      <TopBar dark onMenu={onMenu} />
      <Meter label="내 승차 시각" value={formatTime(party.myBoardAt)} detail={party.meetingPoint} tag="내가 결제자" />
      <div className="block">
        {boarded ? (
          <Say title="순서대로 들러 주세요">기사님께 아래 지점을 차례로 안내해 주세요. 학교에 도착해 결제하면 금액을 입력합니다.</Say>
        ) : (
          <Say title={callNow ? '지금 택시를 호출해 주세요' : `${formatTime(party.callAt)}쯤 택시를 호출해 주세요`}>
            {handedOver ? '앞 승차자가 오지 않아 내가 결제자가 됐습니다.' : '첫 승차자가 결제자입니다.'} {REAL_NAME}
          </Say>
        )}
        <Rail items={stops} />
      </div>
      <div className="band" />
      <div className="block">
        <div className="list"><Item title="분담금과 경로 자세히" detail={won(party.myShare)} onClick={onDetail} /></div>
      </div>
      {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
    </Screen>
  )
}

function SchoolRider({ party, now, run, onDetail, onHome, onMenu }) {
  const [asking, setAsking] = useState(false)
  const boarded = party.boardedAt != null
  const mine = party.members.find((m) => m.requestId === party.myRequestId)
  const missed = !mine.arrived && !party.canArrive
  const wide = useWide()
  const primary = party.canArrive
    ? <Btn onClick={() => run(() => api.arrive(party.id), '도착을 표시했어요')}>도착했어요</Btn>
    : mine.arrived && party.canCancel ? <Arrived /> : null
  return (
    <Screen aside={<RouteSide party={party} mine={mine} />} foot={<Foot party={party} primary={primary} onCancel={() => setAsking(true)} onHome={onHome} />}>
      <TopBar dark onMenu={onMenu} />
      <Meter label={boarded ? '택시 도착까지' : '내 승차 시각까지'} value={remaining(party.myBoardAt, now)}
        detail={`${formatTime(party.myBoardAt)} ${party.meetingPoint}`} />
      <div className="block">
        {missed ? (
          <Say tone="stop-tone" title="승차 시간이 지났어요">도착 표시가 없어 이번 일행에서 빠지고 노쇼로 기록됩니다.</Say>
        ) : mine.arrived && !party.canCancel ? (
          <Say title="이동 중이에요">{party.payerNickname}님이 결제 금액을 입력하면 보낼 돈을 알려 드려요.</Say>
        ) : mine.arrived ? (
          <Say title="도착을 표시했어요">택시가 오면 타 주세요. 택시는 {party.payerNickname}님이 잡습니다.</Say>
        ) : boarded ? (
          <Say title="승차 지점에서 기다려 주세요">{party.payerNickname}님이 택시를 타고 출발했어요.</Say>
        ) : wide && party.canArrive ? null : (
          <Say title="승차 시각에 맞춰 나와 주세요">{party.payerNickname}님이 택시를 타면 남은 시간을 알려 드려요.</Say>
        )}
        {party.canArrive && (
          <Say tone="calm" title={`${formatTime(party.arriveDeadline)}까지 눌러 주세요`}>이때까지 도착 표시가 없으면 일행에서 빠지고, 택시는 다음 지점으로 갑니다.</Say>
        )}
        <Rows items={[['내 예상 분담금', won(party.myShare), 'big'], ['학교 도착', formatTime(party.arrivalAt)]]} />
        <div className="list"><Item title="분담금과 경로 자세히" onClick={onDetail} /></div>
      </div>
      {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
    </Screen>
  )
}

export function PartyScreen(props) {
  if (props.party.direction === 'FROM_HUB') return <HomeParty {...props} />
  return props.party.payer ? <SchoolPayer {...props} /> : <SchoolRider {...props} />
}

export function PartyDetail({ party, myId, onBack }) {
  const mine = party.members.find((m) => m.memberId === myId)
  const toSchool = party.direction === 'TO_SCHOOL'
  const wide = useWide()
  return (
    <Screen aside={mine ? <RouteSide party={party} mine={mine} /> : null}>
      <BackBar onBack={onBack} title={toSchool ? '분담금과 경로' : '일행과 경로'} />
      <div className="block">
        <Rail items={party.members.map((m) => ({
          key: m.requestId,
          pin: m.rideOrder,
          name: m.memberId === myId ? `나 (${m.nickname})` : m.nickname,
          detail: [m.memo, m.pointName, m.memberId === party.payerId ? '결제자' : ''].filter(Boolean).join(', '),
          me: m.memberId === myId,
        }))} />
      </div>
      <div className="band" />
      {mine && !wide && <RouteComparison party={party} mine={mine} />}
      <div className="block">
        {toSchool && <p className="note">승차 시각 3분 뒤까지 도착 표시가 없으면 그 사람을 빼고 갑니다. 분담금은 남은 일행으로 다시 계산하고, 혼자 남으면 일행이 취소됩니다.</p>}
        <p className="note">실제 금액은 결제 후 같은 비율로 나눕니다. {REAL_NAME}</p>
      </div>
    </Screen>
  )
}
