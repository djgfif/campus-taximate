import React, { useState } from 'react'
import { Check } from 'lucide-react'
import { RouteSide } from './RouteComparison.jsx'
import { api } from './api.js'
import { MemberActions } from './MemberSafety.jsx'
import { CancelSheet, DriverView, PartySecondary, PartyShell, REAL_NAME, roleTag } from './PartyShell.jsx'
import { formatDateTime, formatTime, meetPlace, remaining, won } from './format.js'
import { Avatar, Btn, Item, Meter, Rail, Rows, Say } from './ui.jsx'

const Arrived = () => <button type="button" className="btn done" disabled><Check size={20} aria-hidden="true" />도착 표시 완료</button>
const shareRows = (party) => [
  ['내 확정 분담금', won(party.myShare), 'big'],
  ['혼자 탈 때보다', `${won(party.mySoloFare - party.myShare)} 절약`, 'save'],
]

function HomeParty({ party, now, myId, run, onDetail, onHome, onMenu }) {
  const [asking, setAsking] = useState(false)
  const [driver, setDriver] = useState(false)
  const leaving = !party.canArrive && !party.canCancel
  const mine = party.members.find((m) => m.requestId === party.myRequestId)
  const primary = party.canArrive
    ? <Btn onClick={() => run(() => api.arrive(party.id), '도착을 표시했어요')}>도착했어요</Btn>
    : mine?.arrived && !leaving ? <Arrived /> : null
  return <>
    <PartyShell party={party} now={now} onMenu={onMenu} onHome={onHome} foot={primary}
      summary={leaving ? '이동 중' : `출발까지 ${remaining(party.departureAt, now)}`}
      aside={mine ? <RouteSide party={party} mine={mine} /> : null}
      tools={party.payer ? [{ label: '기사님께 보여 주기', onClick: () => setDriver(true) }] : []}
      header={<Meter compact label="출발까지" value={remaining(party.departureAt, now)}
        detail={`${formatTime(party.departureAt)} 출발, ${meetPlace(party)}`} tag={roleTag(party)} />}
      action={leaving ? (
          <Say title="이동 중이에요">
            {party.payer ? '도착해서 결제하면 금액을 입력해 주세요.' : `${party.payerNickname}님이 결제 금액을 입력하면 보낼 돈을 알려 드려요.`}
          </Say>
        ) : party.payer ? (
          <Say title="모두 모이면 택시를 호출해 주세요">마지막에 내리는 사람이 택시를 잡고 결제합니다. {REAL_NAME}</Say>
        ) : (
          <Say title={mine?.arrived ? '도착을 표시했어요' : '시간에 맞춰 정류장으로 가세요'}>택시는 {party.payerNickname}님이 잡습니다.</Say>
        )}
      progress={<div className="block">
        <h2 className="block-title">{party.payer ? '기사님께 알려 줄 하차 순서' : '하차 순서'}</h2>
        <Rail items={party.members.map((m) => ({
          key: m.requestId,
          pin: m.rideOrder,
          avatar: <Avatar name={m.nickname} />,
          name: m.memberId === myId ? `${m.nickname} (나)` : m.nickname,
          detail: m.pointName,
          me: m.memberId === myId,
          state: leaving ? '' : m.arrived ? '도착' : m.nearby ? '2분 안 도착' : '오는 중',
          ok: m.arrived,
        }))} />
        {party.payer && <div className="list"><Item title="기사님께 보여 줄 화면" detail="내릴 곳을 큰 글씨로 보여 드려요" onClick={() => setDriver(true)} /></div>}
      </div>}
      info={<div className="block">
        <Rows items={[['모이는 곳', meetPlace(party)], ['출발 시각', formatDateTime(party.departureAt)], ...shareRows(party)]} />
        <div className="list"><Item title="일행과 경로 자세히" onClick={onDetail} /></div>
        {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
        <PartySecondary party={party} onCancel={() => setAsking(true)} />
      </div>} />
    {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
    {driver && <DriverView party={party} onClose={() => setDriver(false)} />}
  </>
}

function SchoolPayer({ party, now, myId, run, onDetail, onHome, onMenu }) {
  const [asking, setAsking] = useState(false)
  const [driver, setDriver] = useState(false)
  const boarded = party.boardedAt != null
  const handedOver = party.members.some((m) => m.noShow)
  const mine = party.members.find((m) => m.requestId === party.myRequestId)
  const callNow = new Date(party.callAt) <= new Date(now)
  const primary = party.canBoard ? <Btn onClick={() => run(() => api.boarded(party.id), '탑승을 표시했어요')}>택시 탔어요</Btn> : null
  const stops = party.members.map((m) => ({
    key: m.requestId,
    pin: m.rideOrder,
    avatar: <Avatar name={m.nickname} />,
    name: m.memberId === myId ? `${m.nickname} (나)` : m.nickname,
    detail: m.noShow ? '오지 않음' : m.pointName,
    time: formatTime(m.boardAt),
    me: m.memberId === myId,
    off: m.noShow,
  }))
  stops.push({ key: 'school', pin: '학', name: `${party.anchorName} 도착`, detail: '함께 내림', time: formatTime(party.arrivalAt), end: true })
  return <>
    <PartyShell party={party} now={now} onMenu={onMenu} onHome={onHome} foot={primary}
      time={party.myBoardAt} summary={boarded ? '태우러 가는 중' : '내가 결제자'}
      aside={mine ? <RouteSide party={party} mine={mine} /> : null}
      header={<Meter compact label="내 승차 시각" value={formatTime(party.myBoardAt)} detail={meetPlace(party)} tag="내가 결제자" />}
      tools={[{ label: '기사님께 보여 주기', onClick: () => setDriver(true) }]}
      action={boarded ? (
          <Say title="순서대로 들러 주세요">기사님께 아래 지점을 차례로 안내해 주세요. 학교에 도착해 결제하면 금액을 입력합니다.</Say>
        ) : (
          <Say title={callNow ? '지금 택시를 호출해 주세요' : `${formatTime(party.callAt)}쯤 택시를 호출해 주세요`}>
            {handedOver ? '앞 승차자가 오지 않아 내가 결제자가 됐습니다.' : '첫 승차자가 결제자입니다.'} {REAL_NAME}
          </Say>
        )}
      progress={<div className="block">
        <Rail items={stops} />
        <div className="list"><Item title="기사님께 보여 줄 화면" detail="들를 곳을 큰 글씨로 보여 드려요" onClick={() => setDriver(true)} /></div>
      </div>}
      info={<div className="block">
        <Rows items={[['내 확정 분담금', won(party.myShare), 'big'], ['학교 도착', formatTime(party.arrivalAt)]]} />
        <div className="list"><Item title="확정 분담금과 경로 자세히" onClick={onDetail} /></div>
        {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
        <PartySecondary party={party} onCancel={() => setAsking(true)} />
      </div>} />
    {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
    {driver && <DriverView party={party} onClose={() => setDriver(false)} />}
  </>
}

function SchoolRider({ party, now, run, onDetail, onHome, onMenu }) {
  const [asking, setAsking] = useState(false)
  const boarded = party.boardedAt != null
  const mine = party.members.find((m) => m.requestId === party.myRequestId)
  const missed = !mine.arrived && !party.canArrive
  const primary = party.canArrive
    ? <Btn onClick={() => run(() => api.arrive(party.id), '도착을 표시했어요')}>도착했어요</Btn>
    : mine.arrived && party.canCancel ? <Arrived /> : null
  return <>
    <PartyShell party={party} now={now} onMenu={onMenu} onHome={onHome} foot={primary}
      time={party.myBoardAt} summary={boarded ? `택시 오는 중, ${remaining(party.myBoardAt, now)}` : `승차까지 ${remaining(party.myBoardAt, now)}`}
      aside={<RouteSide party={party} mine={mine} />}
      header={<Meter compact label={boarded ? '택시 도착까지' : '내 승차 시각까지'} value={remaining(party.myBoardAt, now)}
        detail={`${formatTime(party.myBoardAt)} 탑승, ${meetPlace(party)}`} tag={roleTag(party)} />}
      action={missed ? (
          <Say tone="stop-tone" title="승차 시간이 지났어요">도착 표시가 없어 이번 일행에서 빠지고 노쇼로 기록됩니다.</Say>
        ) : mine.arrived && !party.canCancel ? (
          <Say title="이동 중이에요">{party.payerNickname}님이 결제 금액을 입력하면 보낼 돈을 알려 드려요.</Say>
        ) : mine.arrived ? (
          <Say title="도착을 표시했어요">택시가 오면 타 주세요. 택시는 {party.payerNickname}님이 잡습니다.</Say>
        ) : boarded ? (
          <Say title="승차 지점에서 기다려 주세요">{party.payerNickname}님이 택시를 타고 출발했어요.</Say>
        ) : party.canArrive ? (
          <Say tone="calm" title={`${formatTime(party.arriveDeadline)}까지 도착을 눌러 주세요`}>이때까지 도착 표시가 없으면 일행에서 빠지고 노쇼로 기록돼요. 30일 안에 두 번이면 7일 동안 새 요청을 할 수 없어요.</Say>
        ) : (
          <Say title="승차 시각에 맞춰 나와 주세요">{party.payerNickname}님이 택시를 타면 남은 시간을 알려 드려요.</Say>
        )}
      progress={<div className="block">
        <Rows items={[['내 확정 분담금', won(party.myShare), 'big'], ['학교 도착', formatTime(party.arrivalAt)]]} />
      </div>}
      info={<div className="block">
        <Rows items={[['내 승차 지점', meetPlace(party)], ['내 승차 시각', formatTime(party.myBoardAt)], ['결제자', party.payerNickname]]} />
        <div className="list"><Item title="확정 분담금과 경로 자세히" onClick={onDetail} /></div>
        {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
        <PartySecondary party={party} onCancel={() => setAsking(true)} />
      </div>} />
    {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
  </>
}

/** 과거 송금 방식·순차 등교 일행 화면(새 요청에는 쓰지 않고 기존 기록 호환용으로 유지한다). */
export default function LegacyParty(props) {
  if (props.party.direction === 'FROM_HUB') return <HomeParty {...props} />
  return props.party.payer ? <SchoolPayer {...props} /> : <SchoolRider {...props} />
}
