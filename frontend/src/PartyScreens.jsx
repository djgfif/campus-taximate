import React, { useEffect, useRef, useState } from 'react'
import { Check, Copy, X } from 'lucide-react'
import RouteComparison, { RouteSide } from './RouteComparison.jsx'
import { api } from './api.js'
import { MemberActions } from './MemberSafety.jsx'
import { PartyChat } from './PartyChat.jsx'
import PaymentCard from './PaymentCard.jsx'
import KakaoTaxi from './KakaoTaxi.jsx'
import { formatTime, formatDateTime, isPaymentParty, partyLabel, remaining, won } from './format.js'
import { BackBar, Btn, Confirm, Item, Meter, Rail, Rows, Say, Screen, Sheet, TopBar, toast, useWide } from './ui.jsx'

// 일행 채팅은 PartyChat.jsx로 옮겼다. 다른 화면은 지금처럼 이 파일에서 가져온다.
export { PartyChat }

const REAL_NAME = '송금할 때 서로의 실명이 표시될 수 있습니다.'

/** 도착 전에는 누르는 버튼, 도착 뒤에는 표시 완료로 바뀐다. */
function Foot({ primary }) { return primary || null }

function PartySecondary({ party, onCancel, onHome, children }) {
  return <section className="block" aria-label="일행 보조 메뉴">
    {children}
    {party.canCancel && <Btn className="quiet" onClick={onCancel}>내 탑승 취소</Btn>}
    <Btn className="line" onClick={onHome}>홈으로</Btn>
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

const shareRows = (party) => [
  ['내 예상 분담금', won(party.myShare), 'big'],
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
  return (
    <Screen aside={mine ? <RouteSide party={party} mine={mine} /> : null} foot={<Foot party={party} primary={primary} onCancel={() => setAsking(true)} onHome={onHome} />}>
      <TopBar dark onMenu={onMenu} />
      <Meter label="출발까지" value={remaining(party.departureAt, now)}
        detail={`${formatTime(party.departureAt)} ${party.meetingPoint}`} tag={party.payer ? '내가 결제자' : '나는 일행원'} />
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
          state: leaving ? '' : m.arrived ? '도착' : m.nearby?'2분 안 도착':'오는 중',
          ok: m.arrived,
        }))} />
      </div>
      <div className="band" />
      <div className="block">
        <Rows items={shareRows(party)} />
        <div className="list">
          {party.payer && <Item title="기사님께 보여 줄 화면" detail="내릴 곳을 큰 글씨로 보여 드려요" onClick={() => setDriver(true)} />}
          <Item title="일행과 경로 자세히" onClick={onDetail} />
        </div>
      </div>
      <PartyChat key={party.id} partyId={party.id} now={now} status={party.status} />
      {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
      <PartySecondary party={party} onCancel={() => setAsking(true)} onHome={onHome} />
      {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
      {driver && <DriverView party={party} onClose={() => setDriver(false)} />}
    </Screen>
  )
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
        <div className="list">
          <Item title="기사님께 보여 줄 화면" detail="들를 곳을 큰 글씨로 보여 드려요" onClick={() => setDriver(true)} />
          <Item title="분담금과 경로 자세히" detail={won(party.myShare)} onClick={onDetail} />
        </div>
      </div>
      <PartyChat key={party.id} partyId={party.id} now={now} status={party.status} />
      {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
      <PartySecondary party={party} onCancel={() => setAsking(true)} onHome={onHome} />
      {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
      {driver && <DriverView party={party} onClose={() => setDriver(false)} />}
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
          <Say tone="calm" title={`${formatTime(party.arriveDeadline)}까지 눌러 주세요`}>이때까지 도착 표시가 없으면 일행에서 빠지고 노쇼로 기록돼요. 30일 안에 두 번이면 7일 동안 새 요청을 할 수 없어요.</Say>
        )}
        <Rows items={[['내 예상 분담금', won(party.myShare), 'big'], ['학교 도착', formatTime(party.arrivalAt)]]} />
        <div className="list"><Item title="분담금과 경로 자세히" onClick={onDetail} /></div>
      </div>
      <PartyChat key={party.id} partyId={party.id} now={now} status={party.status} />
      {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
      <PartySecondary party={party} onCancel={() => setAsking(true)} onHome={onHome} />
      {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
    </Screen>
  )
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
        ['승차 지점', party.anchorName || party.meetingPoint], [party.instant?'예상 출발':'출발 시각', formatDateTime(party.departureAt)],
        ['결제자', party.payerNickname],
        party.freeCancelUntil && ['무료 취소 마감', formatDateTime(party.freeCancelUntil)],
      ]} /><PartyFareRows party={party} /></div>
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
        <p className="note">실제 금액은 결제 후 같은 비율로 나눕니다. {isPaymentParty(party) ? '정산 확정 뒤 선결제와의 차액을 처리해요.' : REAL_NAME}</p>
        <PartyChat key={party.id} partyId={party.id} now={now} status={party.status} />
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
    party.myConfirmedShare!=null ? ['처음 안내된 내 몫',won(party.myConfirmedShare),'big'] : ['내 예상 분담금',won(party.myShare),'big'],
    party.myConfirmedShare!=null && party.myConfirmedShare!==party.myShare && ['현재 예상 분담금',won(party.myShare),'big'],
  ]} />
}

function PaymentParty({ party, now, myId, config, run, onUpdated, onHome, onDetail, onMenu }) {
  const [asking, setAsking] = useState(false)
  const [driver, setDriver] = useState(false)
  const [leavingChange,setLeavingChange]=useState(false)
  const members = party.members || []
  const waiting = ['PREPAYING', 'READY'].includes(party.status)
  const mine=members.find(m=>m.requestId===party.myRequestId)
  const free = party.freeCancelUntil && new Date(now) <= new Date(party.freeCancelUntil)
  const primary = waiting && (party.instant ? !mine?.arrived : party.canArrive)
    ? <Btn onClick={() => run(() => party.instant ? api.prepareParty(party.id, 'ARRIVED') : api.arrive(party.id), '도착을 표시했어요')}>도착했어요</Btn> : null
  return <Screen foot={primary}>
    <TopBar dark onMenu={onMenu} />
    <Meter label={partyLabel(party.status)} value={formatTime(party.departureAt)} detail={party.meetingPoint} tag={party.payer ? '내가 결제자' : '나는 일행원'} />
    <div className="block">
      <Rows items={[
        ['승차 지점', party.anchorName || party.meetingPoint], [party.instant?'예상 출발':'출발 시각', formatDateTime(party.departureAt)],
        ['결제자', party.payerNickname],
        party.freeCancelUntil && ['무료 취소 마감', formatDateTime(party.freeCancelUntil)],
      ]} />
      <PartyFareRows party={party} />
      {party.instant && waiting && <Say>{party.payer ? '확정 즉시 승차 지점으로 출발해 주세요. 나는 선결제하지 않아요.' : <>승차 지점으로 이동하며 선결제를 마쳐 주세요. 선결제 기한은 확정 뒤 {config?.instantMatching?.prepMinutes ?? 3}분이에요.</>}</Say>}
      {party.payer && waiting && <section aria-label="호출 준비">
        <KakaoTaxi />
        <Btn disabled={!party.canCall} onClick={()=>run(async()=>{await api.callParty(party.id);setDriver(true)},'택시를 불렀다고 알렸어요')}>{party.calledAt?'택시 불렀어요 표시 완료':'택시 불렀어요'}</Btn>
        {party.instant&&party.callReady&&<p>모두 준비됐어요. 호출할 수 있어요.</p>}
        {party.notReadyReasons?.map(reason=><p className="note" key={reason}>{reason}</p>)}
        {party.instant&&party.canCall&&!party.callReady&&<Say>예상 출발 시각이 되어 호출할 수 있어요. 아직 도착하지 않은 일행은 탑승 미확인으로 기록해요.</Say>}
        <p className="note">카카오T에서 직접 부른 뒤 일행에게 알려 주세요.</p>
      </section>}
      {party.status === 'PAYER_PENDING' && <Say title="결제자 승계를 기다려요">
        {party.successorId === myId ? <>{formatDateTime(party.successionDeadlineAt)}까지 답해 주세요. 승계하면 택시비를 결제하고, 낸 선결제는 정산 확정 때 전액 환불받아요.
          <Btn onClick={() => run(() => api.payerSuccession(party.id, true), '결제자를 이어받았어요')}>결제자 이어받기</Btn>
          <Btn className="line" onClick={() => run(() => api.payerSuccession(party.id, false), '승계를 거절했어요')}>승계 거절</Btn></> : '새 결제자의 답변을 기다리고 있어요.'}
      </Say>}
      <RideConfirmationCard party={party} onUpdated={onUpdated} />
      {party.adminReviewRequired && <Say tone="stop-tone">{party.reviewReason==='LATE_RIDE_ANSWER_AFTER_REFUND'?'환불 뒤 운행 답변이 접수되어 관리자 확인이 필요해요.':party.reviewReason==='RIDE_DISPUTE'?'운행 답변이 엇갈려 관리자 검토 중이에요. 환불은 보류해요.':'관리자 확인이 필요한 내역이 있어요.'}</Say>}
      {party.status==='RIDE_UNCONFIRMED' && <Say>자동으로 환불하지 않아요. 결제자는 출발 예정 시각에서 24시간 안에 실제 금액을 입력할 수 있어요.</Say>}
      {waiting && <Say>{free ? '무료 취소 마감 전 취소하면 승인된 선결제를 전액 환불해요.' : '무료 취소 마감이 지났어요. 취소하면 남은 일행의 분담금을 다시 계산해요.'}</Say>}
      {party.status === 'PREPAYING' && <Say>{party.payer ? '나는 선결제하지 않아요. 일행의 선결제가 끝나면 택시를 불러 주세요.' : <>기한 안에 선결제해 주세요. 확인 중인 결제는 {party.instant ? '예상 출발 시각' : '호출 시각'}까지 확인해요.</>}</Say>}
      {party.status === 'READY' && <Say>승차 지점에 모여 출발을 준비해 주세요. 택시를 부르는 사람은 {party.payerNickname}님이에요.</Say>}
      {party.status === 'AWAITING_FARE' && <Say>{party.payer ? '실제 금액과 영수증을 입력해 주세요.' : '결제자가 실제 금액과 영수증을 입력하기를 기다리고 있어요.'}</Say>}
      {['DISSOLVED', 'NOT_OPERATED', 'FARE_MISSING'].includes(party.status) && <Say>{party.status==='DISSOLVED' && party.reviewReason==='LATE_CANCELLATION_COMPENSATION'?'무료가 아닌 이탈 금액은 환불을 보류해요. 나머지 승인된 선결제는 전액 환불해요.':'승인된 선결제는 전액 환불 처리해요.'} 카드 반영까지 3~4영업일 정도 걸려요.</Say>}
      {!party.payer && ['PREPAYING', 'READY', 'AWAITING_FARE'].includes(party.status) && <PaymentCard partyId={party.id} myId={myId} now={now} config={config} onUpdated={onUpdated} />}
      <h2 className="block-title">하차 순서</h2>
      <Rail items={[...members].sort((a, b) => a.rideOrder - b.rideOrder).map((m) => ({ key: m.requestId, pin: m.rideOrder,
        name: m.memberId === myId ? '나 (' + m.nickname + ')' : m.nickname,
        detail: [m.pointName, m.memberId === party.payerId ? '결제자' : '', m.confirmedShare!=null ? '처음 안내된 몫 ' + won(m.confirmedShare) + (m.confirmedShare!==m.expectedShare?' · 현재 ' + won(m.expectedShare):'') : m.expectedShare != null ? '예상 ' + won(m.expectedShare) : ''].filter(Boolean).join(' · '), me: m.memberId === myId, state: waiting ? m.noShow ? '불참' : m.arrived ? '도착' : '오는 중' : '', ok: m.arrived,
      }))} />
      <div className="list">{party.payer && <Item title="기사님께 보여 줄 화면" onClick={() => setDriver(true)} />}<Item title="일행과 경로 자세히" onClick={onDetail} /></div>
    </div>
    <PartyChat partyId={party.id} now={now} status={party.status} />
    <PartySecondary party={party} onCancel={() => setAsking(true)} onHome={onHome}>
      {waiting && party.instant && !mine?.arrived && <Btn className="line" disabled={mine?.nearby} onClick={() => run(() => api.prepareParty(party.id, 'NEARBY'), '2분 안 도착으로 표시했어요')}>{mine?.nearby ? '2분 안 도착 표시 완료' : '2분 안 도착'}</Btn>}
      {party.canLeaveAfterChange && <Btn className="line" onClick={() => setLeavingChange(true)}>변경돼서 빠지기</Btn>}
    </PartySecondary>
    {leavingChange && <Sheet title="변경된 일행에서 빠질까요?" onClose={()=>setLeavingChange(false)}>
      <Say title={party.changeExitFree?'무료 이탈':'무료 아님'}>{party.changeExitFree?'호출 시각 전이라 승인된 선결제를 전액 환불해요.':'호출 시각이 지나 무료가 아니에요. 선결제는 환불하지 않고 정산 처리를 기다려요.'}</Say>
      <Btn onClick={async()=>{await run(()=>api.leaveAfterChange(party.id,party.groupVersion),(r)=>r.message);setLeavingChange(false)}}>변경 이탈 확인</Btn>
    </Sheet>}
    {asking && <CancelSheet party={party} now={now} run={run} onClose={() => setAsking(false)} />}
    {driver && <DriverView party={party} onClose={() => setDriver(false)} />}
  </Screen>
}
