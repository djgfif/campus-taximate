import React from 'react'
import RouteComparison from './RouteComparison.jsx'
import { api } from './api.js'
import { formatTime, remaining, won } from './format.js'
import { Hero, MoreLink, Notice, Rows, Screen, Stops, Todo } from './ui.jsx'

const REAL_NAME = '송금할 때 서로의 실명이 표시될 수 있습니다.'

function actions(party, run, primary, onHome) {
  if (!primary && !party.canCancel) return <button className="secondary" onClick={onHome}>홈으로</button>
  return (
    <>
      {primary}
      {party.canCancel && (
        <button className="plain" onClick={() => run(() => api.cancelRequest(party.myRequestId))}>내 탑승 취소</button>
      )}
    </>
  )
}

const shareRows = (party) => [
  ['내 예상 분담금', won(party.myShare), true],
  ['단독 요금보다', `${won(party.mySoloFare - party.myShare)} 절약`, true],
]

function HomeParty({ party, now, run, onDetail, onHome }) {
  const leaving = !party.canArrive && !party.canCancel
  const arrivedNames = party.members.filter((m) => m.memberId !== party.payerId)
  const primary = party.canArrive && <button onClick={() => run(() => api.arrive(party.id))}>도착했어요</button>
  return (
    <Screen actions={actions(party, run, primary, onHome)}>
      <p className="muted">{party.payer ? '일행 확정, 내가 결제자' : '일행 확정'}</p>
      <Hero label="출발까지" value={remaining(party.departureAt, now)}
        detail={`${formatTime(party.departureAt)} ${party.meetingPoint}`} />
      {leaving ? (
        <Todo title="이동 중이에요">
          {party.payer ? '도착해서 결제하면 금액을 입력해 주세요.' : `${party.payerNickname}님이 결제 금액을 입력하면 보낼 돈을 알려 드려요.`}
        </Todo>
      ) : party.payer ? (
        <Todo title="내가 택시를 잡고 결제해요">마지막에 내리는 사람이 결제자입니다. 모두 모이면 택시를 호출해 주세요. {REAL_NAME}</Todo>
      ) : party.canArrive ? (
        <Todo title="시간에 맞춰 정류장으로 가세요">도착하면 아래 버튼을 눌러 주세요. 택시는 {party.payerNickname}님이 잡습니다.</Todo>
      ) : (
        <Todo title="도착을 표시했어요">택시는 {party.payerNickname}님이 잡습니다.</Todo>
      )}
      {party.payer && !leaving && (
        <>
          <Rows title="지금 도착한 일행" items={arrivedNames.map((m) => [m.nickname, m.arrived ? '도착' : '오는 중', m.arrived])} />
          <Rows title="기사님께 알려 줄 하차 순서" items={party.members.map((m) => [String(m.rideOrder), `${m.nickname}, ${m.pointName}`])} />
        </>
      )}
      <Rows items={shareRows(party)} />
      <MoreLink label="일행과 경로 자세히" detail={`${party.members.length}명`} onClick={onDetail} />
    </Screen>
  )
}

function SchoolPayer({ party, myId, run, onDetail, onHome }) {
  const boarded = party.boardedAt != null
  const handedOver = party.members.some((m) => m.noShow)
  const primary = party.canBoard && <button onClick={() => run(() => api.boarded(party.id))}>택시 탔어요</button>
  const stops = party.members.map((m) => ({
    key: m.requestId,
    badge: m.rideOrder,
    name: m.memberId === myId ? `나 (${m.nickname})` : m.nickname,
    detail: m.noShow ? '오지 않음' : m.pointName,
    right: formatTime(m.boardAt),
    me: m.memberId === myId,
  }))
  stops.push({ key: 'school', badge: '학', name: `${party.anchorName} 도착`, detail: '함께 내림', right: formatTime(party.arrivalAt) })
  return (
    <Screen actions={actions(party, run, primary, onHome)}>
      <p className="muted">일행 확정, 내가 결제자</p>
      <Hero label="내 승차 시각" value={formatTime(party.myBoardAt)} detail={party.meetingPoint} />
      {boarded ? (
        <Todo title="순서대로 들러 주세요">다음 일행에게 남은 시간이 보입니다. 학교에 도착해 결제하면 금액을 입력해 주세요.</Todo>
      ) : (
        <Todo title={`${formatTime(party.callAt)}쯤 택시를 호출해 주세요`}>
          {handedOver ? '앞 승차자가 오지 않아 내가 결제자가 됐습니다.' : '첫 승차자가 결제자입니다.'} 탑승하면 아래 버튼을 눌러 주세요. {REAL_NAME}
        </Todo>
      )}
      <Stops items={stops} />
      <MoreLink label="분담금과 경로 자세히" detail={won(party.myShare)} onClick={onDetail} />
    </Screen>
  )
}

function SchoolRider({ party, now, run, onDetail, onHome }) {
  const boarded = party.boardedAt != null
  const mine = party.members.find((m) => m.requestId === party.myRequestId)
  const missed = !mine.arrived && !party.canArrive
  const primary = party.canArrive && <button onClick={() => run(() => api.arrive(party.id))}>도착했어요</button>
  return (
    <Screen actions={actions(party, run, primary, onHome)}>
      <p className="muted">일행 확정</p>
      <Hero label={boarded ? '택시 도착까지' : '내 승차 시각까지'} value={remaining(party.myBoardAt, now)}
        detail={`${formatTime(party.myBoardAt)} ${party.meetingPoint}`} />
      {missed ? (
        <Todo title="승차 시간이 지났어요">도착 표시가 없어 이번 일행에서 빠집니다. 노쇼로 기록됩니다.</Todo>
      ) : mine.arrived && !party.canCancel ? (
        <Todo title="이동 중이에요">{party.payerNickname}님이 결제 금액을 입력하면 보낼 돈을 알려 드려요.</Todo>
      ) : mine.arrived ? (
        <Todo title="도착을 표시했어요">택시가 오면 타 주세요. 택시는 {party.payerNickname}님이 잡습니다.</Todo>
      ) : boarded ? (
        <Todo title="승차 지점에서 기다려 주세요">{party.payerNickname}님이 택시를 타고 출발했어요. 도착하면 아래 버튼을 눌러 주세요.</Todo>
      ) : (
        <Todo title="승차 시각에 맞춰 나와 주세요">{party.payerNickname}님이 택시를 타면 남은 시간을 알려 드려요.</Todo>
      )}
      {party.canArrive && (
        <Notice title={`${formatTime(party.arriveDeadline)}까지 눌러 주세요`}>
          이때까지 도착 표시가 없으면 일행에서 빠지고, 택시는 다음 지점으로 갑니다.
        </Notice>
      )}
      <Rows items={[['내 예상 분담금', won(party.myShare), true], ['학교 도착', formatTime(party.arrivalAt)]]} />
      <MoreLink label="분담금과 경로 자세히" detail={`${party.members.length}명`} onClick={onDetail} />
    </Screen>
  )
}

export function PartyScreen({ party, now, myId, run, onDetail, onHome }) {
  if (party.direction === 'FROM_HUB') return <HomeParty party={party} now={now} run={run} onDetail={onDetail} onHome={onHome} />
  return party.payer
    ? <SchoolPayer party={party} myId={myId} run={run} onDetail={onDetail} onHome={onHome} />
    : <SchoolRider party={party} now={now} run={run} onDetail={onDetail} onHome={onHome} />
}

export function PartyDetail({ party, myId, onBack }) {
  const mine = party.members.find((m) => m.memberId === myId)
  const toSchool = party.direction === 'TO_SCHOOL'
  return (
    <Screen actions={<button onClick={onBack}>돌아가기</button>}>
      <h2>{toSchool ? '분담금과 경로' : '일행과 경로'}</h2>
      <Stops items={party.members.map((m) => ({
        key: m.requestId,
        badge: m.rideOrder,
        name: m.memberId === myId ? `나 (${m.nickname})` : m.nickname,
        detail: [m.memo, m.pointName, m.memberId === party.payerId ? '결제자' : ''].filter(Boolean).join(', '),
        me: m.memberId === myId,
      }))} />
      <Rows title="내 요금" items={[
        ['단독 요금', won(party.mySoloFare)],
        ['내 예상 분담금', won(party.myShare), true],
        ['절약', won(party.mySoloFare - party.myShare), true],
      ]} />
      {mine && <RouteComparison party={party} mine={mine} />}
      {toSchool && <p className="muted">일행이 오지 않으면: 승차 시각 3분 뒤까지 도착 표시가 없으면 그 사람을 빼고 갑니다. 분담금은 남은 일행으로 다시 계산합니다. 혼자 남으면 일행이 취소됩니다.</p>}
      <p className="muted">실제 금액은 결제 후 같은 비율로 나눕니다. {REAL_NAME}</p>
    </Screen>
  )
}
