import React, { useEffect, useRef, useState } from 'react'
import { Check, Copy, X } from 'lucide-react'
import RouteComparison, { PartyMiniMap, RouteSide } from './RouteComparison.jsx'
import { api } from './api.js'
import { MemberActions } from './MemberSafety.jsx'
import { CHAT_RETENTION, PartyChat } from './PartyChat.jsx'
import PaymentCard from './PaymentCard.jsx'
import { trustLine, usePeople } from './People.jsx'
import KakaoTaxi from './KakaoTaxi.jsx'
import { formatTime, formatDateTime, isPaymentParty, knownPartyStatus, meetPlace, partyLabel, remaining, won } from './format.js'
import { Avatar, BackBar, Btn, Confirm, Item, Meter, Rail, Rows, Say, Screen, Sheet, TopBar, toast, useWide } from './ui.jsx'

// 일행 채팅은 PartyChat.jsx로 옮겼다. 다른 화면은 지금처럼 이 파일에서 가져온다.
export { PartyChat }

const REAL_NAME = '송금할 때 서로의 실명이 표시될 수 있습니다.'

/** 일행 화면의 보조 동작. 홈은 위쪽 뒤로 버튼이 맡고, 여기는 정보 탭 맨 아래의 취소만 둔다. */
function PartySecondary({ party, onCancel }) {
  if (!party.canCancel) return null
  return <section className="secondary" aria-label="일행 보조 메뉴">
    <Btn className="quiet" onClick={onCancel}>내 탑승 취소</Btn>
  </section>
}

const Arrived = () => <button type="button" className="btn done" disabled><Check size={20} aria-hidden="true" />도착 표시 완료</button>

function CancelSheet({ party, now, run, onClose }) {
  return (
    <Confirm title="탑승을 취소할까요?" action="탑승 취소" onClose={onClose}
      onConfirm={() => run(() => api.cancelRequest(party.myRequestId), '탑승을 취소했어요')}>
      {party.freeCancelUntil && <span>{formatDateTime(party.freeCancelUntil)}까지 무료 취소예요.
        {now && new Date(now) <= new Date(party.freeCancelUntil) ? ' 선결제했으면 전액 환불해요.' : ' 무료 취소 마감이 지났어요.'}<br /></span>}
      남은 일행의 분담금을 다시 계산하고, 한 명만 남으면 해산해요.
    </Confirm>
  )
}

/** 결제자가 기사님께 보여 주는 화면. 일반호출은 경유를 넣을 수 없어 들를 곳을 순서대로 크게 보여 준다. */
export function DriverView({ party, onClose }) {
  const toSchool = party.direction === 'TO_SCHOOL'
  const stops = party.members.filter((m) => !m.noShow).sort((a, b) => a.rideOrder - b.rideOrder)
  const pickups = toSchool ? stops.filter((m) => m.memberId !== party.payerId) : stops
  const callTo = pickups[0]?.pointName || party.anchorName
  useEffect(() => {
    const key = (event) => event.key === 'Escape' && onClose()
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [onClose])
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(callTo)
      toast('호출 목적지를 복사했어요')
    } catch {
      toast('복사하지 못했습니다. 직접 입력해 주세요.', 'bad')
    }
  }
  return (
    <div className="driver" role="dialog" aria-modal="true" aria-label="기사님께 보여 줄 화면">
      <header>
        <b>{toSchool ? '이 순서로 태워 주세요' : '이 순서로 내려 주세요'}</b>
        <button type="button" className="icon-btn" aria-label="닫기" onClick={onClose}><X size={28} /></button>
      </header>
      <ol>
        {pickups.map((m, i) => (
          <li key={m.requestId}><span>{i + 1}</span><b>{m.pointName}</b></li>
        ))}
        {toSchool && <li className="end"><span>학</span><b>{party.anchorName}</b><small>모두 내림</small></li>}
      </ol>
      <div className="driver-call">
        <small>호출 목적지로 먼저 넣을 곳</small>
        <b>{callTo}</b>
        <Btn className="small line" onClick={copy}><Copy size={16} aria-hidden="true" />복사</Btn>
      </div>
    </div>
  )
}

const TABS = [['progress', '진행'], ['chat', '채팅'], ['info', '정보']]
const inTest = () => typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent)

/**
 * 모든 일행 화면의 뼈대. 짧은 머리(시각·모이는 곳·역할) 바로 아래에 탭(진행·채팅·정보)을 붙이고,
 * 탭을 바꾸면 탭이 화면 위에 오도록 올린다. 채팅은 탭 아래 남은 화면을 모두 쓰는 대화방으로 열고,
 * 하단 고정 버튼은 채팅 탭에서는 숨긴다.
 */
export function PartyShell({ party, now, onMenu, onHome, header, progress, info, foot, aside, dark = true, firstTab = '진행' }) {
  const [tab, setTab] = useState('progress')
  const tabs = useRef(null)
  const choose = (next) => {
    setTab(next)
    if (inTest() || !tabs.current) return
    const bar = document.querySelector('.bar')?.offsetHeight || 56
    const top = next === 'progress' ? 0 : tabs.current.getBoundingClientRect().top + window.scrollY - bar
    window.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
  }
  const unread = party.unreadChat || 0
  return <Screen aside={aside} foot={tab === 'chat' ? null : foot}>
    <TopBar dark={dark} onMenu={onMenu} onHome={onHome} />
    {header}
    <div ref={tabs} className="party-tabs" role="tablist" aria-label="일행 화면">
      {TABS.map(([id, label]) => <button key={id} type="button" role="tab" id={`tab-${id}`} aria-controls={`panel-${id}`}
        aria-selected={tab === id} className={tab === id ? 'on' : ''} onClick={() => choose(id)}>
        {id === 'progress' ? firstTab : label}
        {id === 'chat' && unread > 0 && tab !== 'chat' && <span className="unread-badge" aria-label={`안 읽은 메시지 ${unread}개`}>{unread > 99 ? '99+' : unread}</span>}
      </button>)}
    </div>
    <section id="panel-progress" role="tabpanel" aria-labelledby="tab-progress" hidden={tab !== 'progress'} className="party-panel">{progress}</section>
    <section id="panel-chat" role="tabpanel" aria-labelledby="tab-chat" hidden={tab !== 'chat'}>
      <PartyChat key={party.id} partyId={party.id} now={now} status={party.status} active={tab === 'chat'} full />
    </section>
    <section id="panel-info" role="tabpanel" aria-labelledby="tab-info" hidden={tab !== 'info'} className="party-panel">{info}</section>
  </Screen>
}

const shareRows = (party) => [
  ['내 예상 분담금', won(party.myShare), 'big'],
  ['혼자 탈 때보다', `${won(party.mySoloFare - party.myShare)} 절약`, 'save'],
]
const roleTag = (party) => (party.payer ? '내가 결제자' : '나는 일행원')

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
      aside={mine ? <RouteSide party={party} mine={mine} /> : null}
      header={<Meter compact label="출발까지" value={remaining(party.departureAt, now)}
        detail={`${formatTime(party.departureAt)} · ${meetPlace(party)}`} tag={roleTag(party)} />}
      progress={<div className="block">
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
      aside={mine ? <RouteSide party={party} mine={mine} /> : null}
      header={<Meter compact label="내 승차 시각" value={formatTime(party.myBoardAt)} detail={meetPlace(party)} tag="내가 결제자" />}
      progress={<div className="block">
        {boarded ? (
          <Say title="순서대로 들러 주세요">기사님께 아래 지점을 차례로 안내해 주세요. 학교에 도착해 결제하면 금액을 입력합니다.</Say>
        ) : (
          <Say title={callNow ? '지금 택시를 호출해 주세요' : `${formatTime(party.callAt)}쯤 택시를 호출해 주세요`}>
            {handedOver ? '앞 승차자가 오지 않아 내가 결제자가 됐습니다.' : '첫 승차자가 결제자입니다.'} {REAL_NAME}
          </Say>
        )}
        <Rail items={stops} />
        <div className="list"><Item title="기사님께 보여 줄 화면" detail="들를 곳을 큰 글씨로 보여 드려요" onClick={() => setDriver(true)} /></div>
      </div>}
      info={<div className="block">
        <Rows items={[['내 예상 분담금', won(party.myShare), 'big'], ['학교 도착', formatTime(party.arrivalAt)]]} />
        <div className="list"><Item title="분담금과 경로 자세히" onClick={onDetail} /></div>
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
      aside={<RouteSide party={party} mine={mine} />}
      header={<Meter compact label={boarded ? '택시 도착까지' : '내 승차 시각까지'} value={remaining(party.myBoardAt, now)}
        detail={`${formatTime(party.myBoardAt)} · ${meetPlace(party)}`} tag={roleTag(party)} />}
      progress={<div className="block">
        {missed ? (
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
        <Rows items={[['내 예상 분담금', won(party.myShare), 'big'], ['학교 도착', formatTime(party.arrivalAt)]]} />
      </div>}
      info={<div className="block">
        <Rows items={[['내 승차 지점', meetPlace(party)], ['내 승차 시각', formatTime(party.myBoardAt)], ['결제자', party.payerNickname]]} />
        <div className="list"><Item title="분담금과 경로 자세히" onClick={onDetail} /></div>
        {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
        <PartySecondary party={party} onCancel={() => setAsking(true)} />
      </div>} />
    {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
  </>
}

export function PartyScreen(props) {
  if (isPaymentParty(props.party)) return <PaymentParty {...props} />
  if (props.party.direction === 'FROM_HUB') return <HomeParty {...props} />
  return props.party.payer ? <SchoolPayer {...props} /> : <SchoolRider {...props} />
}

export function PartyDetail({ party, myId, now, onBack }) {
  const mine = party.members.find((m) => m.memberId === myId)
  const toSchool = party.direction === 'TO_SCHOOL'
  const wide = useWide()
  return (
    <Screen aside={mine ? <RouteSide party={party} mine={mine} /> : null}>
      <BackBar onBack={onBack} title={toSchool ? '분담금과 경로' : '일행과 경로'} />
      <div className="block"><Rows items={[
        ['모이는 곳', meetPlace(party)], [party.instant?'예상 출발':'출발 시각', formatDateTime(party.departureAt)],
        ['결제자', party.payerNickname],
        party.freeCancelUntil && ['무료 취소 마감', formatDateTime(party.freeCancelUntil)],
      ]} /><PartyFareRows party={party} /></div>
      <div className="block">
        <Rail items={party.members.map((m) => ({
          key: m.requestId,
          pin: m.rideOrder,
          avatar: <Avatar name={m.nickname} />,
    name: m.memberId === myId ? `${m.nickname} (나)` : m.nickname,
          detail: [m.memo, m.pointName, m.memberId === party.payerId ? '결제자' : ''].filter(Boolean).join(', '),
          me: m.memberId === myId,
        }))} />
      </div>
      <div className="band" />
      {mine && !wide && <RouteComparison party={party} mine={mine} />}
      <div className="block">
        {toSchool && <p className="note">승차 시각 3분 뒤까지 도착 표시가 없으면 그 사람을 빼고 갑니다. 분담금은 남은 일행으로 다시 계산하고, 혼자 남으면 일행이 취소됩니다.</p>}
        <p className="note">실제 금액은 결제 후 같은 비율로 나눕니다. {isPaymentParty(party) ? '정산 확정 뒤 선결제와의 차액을 처리해요.' : REAL_NAME}</p>
        {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
      </div>
    </Screen>
  )
}

/** 서버가 허용한 본인 답만 전송한다. 두 버튼의 중복 누름도 함께 막는다. */
export function RideConfirmationCard({ party, onUpdated }) {
  const [saved,setSaved]=useState(null)
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  const sending=useRef(false)
  const answer=party.myRideAnswer ?? (saved?.id===party.id?saved.answer:null)
  const submit=async(rode)=>{
    if(sending.current || answer!=null) return
    sending.current=true; setBusy(true); setError('')
    try { await api.confirmRide(party.id,rode); setSaved({id:party.id,answer:rode}); await onUpdated?.() }
    catch(e) { setError(e.message) }
    finally { sending.current=false; setBusy(false) }
  }
  if(!party.canConfirmRide && answer==null && party.status!=='RIDE_UNCONFIRMED') return null
  return <section className="payment-card" aria-label="운행 확인">
    <h2>{answer!=null?'내 운행 답변':'운행 확인 중'}</h2><p>일행과 택시에 탔나요? 첫 답변은 바꿀 수 없어요.</p>
    {answer!=null ? <Say>내 답변: {answer?'탔어요':'못 탔어요'}</Say>
      : party.canConfirmRide ? <div className="btns"><Btn disabled={busy} onClick={()=>submit(true)}>탔어요</Btn><Btn className="line" disabled={busy} onClick={()=>submit(false)}>못 탔어요</Btn></div>
        : <p>운행 확인 결과를 기다리고 있어요.</p>}
    {error && <p role="alert">{error}</p>}
  </section>
}

export function PartyFareRows({ party }) {
  return <Rows items={[
    party.myConfirmedShare!=null ? ['확정 때 내 몫',won(party.myConfirmedShare),'big'] : ['내 예상 몫',won(party.myShare),'big'],
    party.myConfirmedShare!=null && party.myConfirmedShare!==party.myShare && ['지금 예상 몫',won(party.myShare),'big'],
  ]} />
}

const STEPS = ['확정', '선결제', '모이기', '탑승', '정산']

/** 일행 상태를 다섯 단계 중 하나로 보여 준다. 배달 앱처럼 "지금 어디쯤"을 한눈에. */
function stepOf(party) {
  if (['AWAITING_FARE', 'OBJECTION_WINDOW', 'REVIEW_PENDING', 'SETTLED', 'COMPLETED', 'FARE_MISSING'].includes(party.status)) return 4
  if (party.status === 'RIDE_UNCONFIRMED' || party.startedAt) return 3
  if (party.status === 'READY' || party.status === 'PAYER_PENDING') return party.calledAt ? 3 : 2
  if (party.status === 'PREPAYING') return 1
  return 0
}

export function PartyStepper({ party }) {
  const ended = ['DISSOLVED', 'NOT_OPERATED'].includes(party.status)
  const current = stepOf(party)
  return <ol className={`stepper${ended ? ' ended' : ''}`} aria-label="진행 단계">
    {STEPS.map((step, i) => <li key={step} className={i < current ? 'done' : i === current ? 'now' : ''}
      aria-current={i === current ? 'step' : undefined}><span aria-hidden="true" />{step}</li>)}
  </ol>
}

/** 상태와 역할에 맞는 "지금 할 일" 하나. 제목·한 줄 설명·남은 시간과, 필요하면 그 자리에서 누르는 카드. */
function NextAction({ party, now, myId, mine, config, run, onUpdated, onDriver }) {
  const waiting = ['PREPAYING', 'READY'].includes(party.status)
  const left = (at) => (at && now ? `${remaining(at, now)} 남음` : '')
  let title
  let detail = ''
  let extra = null
  let tone = ''
  if (party.status === 'PAYER_PENDING') {
    const me = party.successorId === myId
    title = me ? '결제자를 이어받을까요?' : '새 결제자를 정하고 있어요'
    detail = me
      ? `${formatTime(party.successionDeadlineAt)}까지 답해 주세요. 이어받으면 내 선결제는 정산 때 전액 돌려받아요.`
      : '원래 결제자가 오지 않아 다음 사람에게 묻고 있어요.'
    if (me) extra = <div className="btns">
      <Btn onClick={() => run(() => api.payerSuccession(party.id, true), '결제자를 이어받았어요')}>결제자 이어받기</Btn>
      <Btn className="line" onClick={() => run(() => api.payerSuccession(party.id, false), '승계를 거절했어요')}>승계 거절</Btn>
    </div>
  } else if (party.status === 'RIDE_UNCONFIRMED' || party.canConfirmRide) {
    title = '택시에 탔나요?'
    detail = '운행 시작 표시가 없어 확인하고 있어요. 미운행이 확인될 때만 환불해요.'
    extra = <RideConfirmationCard party={party} onUpdated={onUpdated} />
  } else if (party.status === 'PREPAYING' && !party.payer) {
    title = '선결제를 마쳐 주세요'
    detail = '내 몫에 여유 15%를 더해 미리 내고, 내린 뒤 실제 요금으로 나눠 남는 돈은 돌려받아요.'
    extra = <PaymentCard compact partyId={party.id} myId={myId} now={now} config={config} onUpdated={onUpdated} />
  } else if (waiting && party.payer) {
    if (party.calledAt) {
      title = '택시를 불렀어요'
      detail = '기사님께 내릴 순서를 보여 주세요.'
      extra = <Btn className="line" onClick={onDriver}>기사님께 보여 줄 화면</Btn>
    } else {
      // 결제자는 선결제가 없다. 호출 버튼은 늘 보이되, 준비가 안 되면 꺼 두고 이유를 보여 준다.
      if (party.canCall) {
        title = '이제 택시를 불러 주세요'
        detail = '카카오T에서 부른 뒤 "택시 불렀어요"를 누르면 일행에게 알려져요.'
      } else if (party.instant) {
        title = '확정 즉시 승차 지점으로 출발해 주세요'
        detail = '나는 선결제하지 않아요. 다들 결제하고 도착하면 택시를 부를 수 있어요.'
      } else {
        title = party.status === 'PREPAYING' ? '다른 사람들이 결제하는 중이에요' : `${formatTime(party.callAt || party.departureAt)}쯤 택시를 불러요`
        detail = '나는 선결제하지 않아요. 부를 때가 되면 버튼이 켜져요.'
        tone = 'calm'
      }
      extra = <>
        {!party.canCall && party.notReadyReasons?.map((reason) => <p className="note" key={reason}>{reason}</p>)}
        {party.instant && party.canCall && !party.callReady && <Say>예상 출발 시각이 되어 호출할 수 있어요. 아직 도착하지 않은 일행은 탑승 미확인으로 기록해요.</Say>}
        <KakaoTaxi disabled={!party.canCall} />
        <Btn disabled={!party.canCall} onClick={() => run(async () => { await api.callParty(party.id); onDriver() }, '택시를 불렀다고 알렸어요')}>택시 불렀어요</Btn>
      </>
    }
  } else if (waiting) {
    const goBy = mine?.boardAt || party.departureAt
    const place = meetPlace(party)
    if (mine?.arrived) {
      title = '도착 표시를 했어요'
      detail = `택시는 ${party.payerNickname}님이 불러요. 어디 있는지 채팅으로 알려 주세요.`
      tone = 'calm'
    } else {
      title = `${formatTime(goBy)}까지 ${place}에서 만나요`
      detail = `${left(goBy)} · 도착하면 "도착했어요"를 눌러 주세요.`
    }
  } else if (party.status === 'AWAITING_FARE') {
    title = party.payer ? '실제 금액을 입력해 주세요' : '결제자의 금액 입력을 기다려요'
    detail = party.payer ? '영수증 사진과 함께 올리면 같은 비율로 나눠요.' : '입력되면 24시간 동안 요금 이의를 낼 수 있어요.'
    if (!party.payer) tone = 'calm'
  } else if (['DISSOLVED', 'NOT_OPERATED', 'FARE_MISSING'].includes(party.status)) {
    title = party.status === 'DISSOLVED' ? '일행이 해산됐어요' : '운행이 성사되지 않았어요'
    detail = party.reviewReason === 'LATE_CANCELLATION_COMPENSATION'
      ? '무료가 아닌 이탈 금액은 정산 처리를 기다리고, 나머지 선결제는 환불해요.'
      : '승인된 선결제는 전액 환불해요. 카드 반영까지 3~4영업일 정도 걸려요.'
    tone = 'calm'
  } else if (!party.adminReviewRequired) {
    // 알릴 할 일이 없는 상태(이의 기간 등)는 위 상태 표시로 충분하다.
    return null
  } else {
    title = '관리자 검토 중이에요'
    tone = 'calm'
  }
  return <section className={`next-action ${tone}`} aria-label="지금 할 일">
    <h2>{title}</h2>
    {detail && <p>{detail}</p>}
    {party.adminReviewRequired && <Say tone="stop-tone">{party.reviewReason === 'RIDE_DISPUTE'
      ? '운행 답변이 엇갈려 관리자 검토 중이에요. 환불은 보류해요.' : '관리자 확인이 필요한 내역이 있어요.'}</Say>}
    {extra}
  </section>
}

/**
 * 결제 흐름 일행 화면. 머리(출발 시각·모이는 곳·역할) 아래 탭 세 개.
 * 진행 탭: 진행 단계 → 지금 할 일 → 하차 순서. 정보 탭: 요약·규칙·취소. 채팅 탭: 전체 화면 대화방.
 */
function PaymentParty({ party, now, myId, config, run, onUpdated, onHome, onDetail, onMenu }) {
  const [asking, setAsking] = useState(false)
  const [driver, setDriver] = useState(false)
  const [leavingChange, setLeavingChange] = useState(false)
  const members = party.members || []
  const waiting = ['PREPAYING', 'READY'].includes(party.status)
  const mine = members.find((m) => m.requestId === party.myRequestId)
  const free = party.freeCancelUntil && new Date(now) <= new Date(party.freeCancelUntil)
  const owesPrepay = party.status === 'PREPAYING' && !party.payer
  const canArrive = waiting && !owesPrepay && (party.instant ? !mine?.arrived : party.canArrive)
  const primary = canArrive
    ? <Btn onClick={() => run(() => (party.instant ? api.prepareParty(party.id, 'ARRIVED') : api.arrive(party.id)), '도착을 표시했어요')}>도착했어요</Btn> : null
  const order = [...members].sort((a, b) => a.rideOrder - b.rideOrder)
  const [people] = usePeople(party.id, party.groupVersion)
  const personOf = (memberId) => people.find((p) => p.memberId === memberId)
  return <>
    <PartyShell party={party} now={now} onMenu={onMenu} onHome={onHome} foot={primary}
      header={<Meter compact label={!knownPartyStatus(party.status) ? partyLabel(party.status) : party.instant ? '출발(예상)' : '출발'} value={formatTime(party.departureAt)} detail={meetPlace(party)} tag={roleTag(party)} />}
      progress={<>
        <div className="block party-top">
          <PartyStepper party={party} />
          <NextAction party={party} now={now} myId={myId} mine={mine} config={config} run={run} onUpdated={onUpdated} onDriver={() => setDriver(true)} />
          {party.canLeaveAfterChange && <Say tone="stop-tone" title="일행이 바뀌었어요">현재 내 몫은 {won(party.myShare)}이에요. 원하지 않으면 {party.changeExitFree ? '호출 전까지 무료로' : ''} 빠질 수 있어요.
            <Btn className="line" onClick={() => setLeavingChange(true)}>변경돼서 빠지기</Btn></Say>}
          {waiting && party.instant && !mine?.arrived && !owesPrepay && <Btn className="line" disabled={mine?.nearby}
            onClick={() => run(() => api.prepareParty(party.id, 'NEARBY'), '2분 안 도착으로 표시했어요')}>{mine?.nearby ? '2분 안 도착 표시 완료' : '2분 안 도착'}</Btn>}
        </div>
        <div className="band" />
        <div className="block">
          {mine && <PartyMiniMap party={party} mine={mine} />}
          <h2 className="block-title">같이 타는 사람 <small>내리는 순서</small></h2>
          <Rail items={order.map((m) => ({
            key: m.requestId,
            pin: m.rideOrder,
            avatar: <Avatar name={m.nickname} />,
            name: m.memberId === myId ? m.nickname + ' (나)' : m.nickname,
            badge: m.memberId === party.payerId ? '택시 부름' : '',
            detail: [m.pointName, m.expectedShare != null ? won(m.expectedShare) : ''].filter(Boolean).join(' · '),
            sub: m.memberId === myId ? '' : trustLine(personOf(m.memberId)),
            me: m.memberId === myId,
            state: waiting ? (m.noShow ? '불참' : m.arrived ? '도착' : m.nearby ? '2분 안' : '오는 중') : '',
            ok: m.arrived,
          }))} />
          {party.payer && <div className="list"><Item title="기사님께 보여 줄 화면" onClick={() => setDriver(true)} /></div>}
        </div>
      </>}
      info={<div className="block">
        <Rows items={[
          ['모이는 곳', meetPlace(party)],
          [party.instant ? '예상 출발' : '출발 시각', formatDateTime(party.departureAt)],
          ['결제자', party.payerNickname],
          party.freeCancelUntil && ['무료 취소 마감', formatDateTime(party.freeCancelUntil)],
        ]} />
        <PartyFareRows party={party} />
        <details className="rules">
          <summary>규칙 자세히</summary>
          <ul>
            <li>{free ? '무료 취소 마감 전 취소하면 승인된 선결제를 전액 환불해요.' : '무료 취소 마감이 지나 취소하면 남은 일행의 늘어난 몫을 보전해요.'}</li>
            <li>선결제는 확정 때 내 몫에 여유 15%를 더한 금액이에요. 길이 막히면 요금이 늘 수 있어서 조금 더 받아 두고, 실제 요금으로 나눈 뒤 남으면 돌려주고 모자라면 더 받아요.</li>
            {party.instant && <li>즉시 일행은 선결제 기한이 확정 뒤 {config?.instantMatching?.prepMinutes ?? 3}분이고, 모두 준비되면 바로 호출해요.</li>}
            <li>택시는 결제자가 카카오T로 직접 불러요. 앱은 부른 사실만 기록해요.</li>
            <li>운행 시작 표시가 없으면 탔는지 물어보고, 미운행이 확인될 때만 환불해요.</li>
            <li>{CHAT_RETENTION}</li>
          </ul>
        </details>
        <div className="list"><Item title="일행과 경로 자세히" onClick={onDetail} /></div>
        <PartySecondary party={party} onCancel={() => setAsking(true)} />
      </div>} />
    {leavingChange && <Sheet title="변경된 일행에서 빠질까요?" onClose={() => setLeavingChange(false)}>
      <Say title={party.changeExitFree ? '무료 이탈' : '무료 아님'}>{party.changeExitFree ? '호출 시각 전이라 승인된 선결제를 전액 환불해요.' : '호출 시각이 지나 무료가 아니에요. 선결제는 환불하지 않고 정산 처리를 기다려요.'}</Say>
      <Btn onClick={async () => { await run(() => api.leaveAfterChange(party.id, party.groupVersion), (r) => r.message); setLeavingChange(false) }}>변경 이탈 확인</Btn>
    </Sheet>}
    {asking && <CancelSheet party={party} now={now} run={run} onClose={() => setAsking(false)} />}
    {driver && <DriverView party={party} onClose={() => setDriver(false)} />}
  </>
}
