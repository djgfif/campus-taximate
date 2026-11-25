import React, { useRef, useState } from 'react'
import RouteComparison, { figures, PartyMiniMap, RouteSide } from './RouteComparison.jsx'
import { api } from './api.js'
import { MemberActions } from './MemberSafety.jsx'
import { CHAT_RETENTION, PartyChat } from './PartyChat.jsx'
import PaymentCard from './PaymentCard.jsx'
import { trustLine, usePeople } from './People.jsx'
import KakaoTaxi from './KakaoTaxi.jsx'
import BoardingCodePanel from './BoardingCodePanel.jsx'
import LegacyParty from './LegacyPartyScreens.jsx'
import { CancelSheet, DriverView, PartySecondary, PartyShell, REAL_NAME, roleTag } from './PartyShell.jsx'
import { formatTime, formatDateTime, isPaymentParty, knownPartyStatus, meetPlace, partyLabel, remaining, won } from './format.js'
import { Avatar, BackBar, Btn, FairnessNote, FareCompare, Fold, Item, Meter, Rail, Rows, Say, Screen, Sheet, useWide } from './ui.jsx'

// 일행 채팅과 공통 방 뼈대는 별도 파일로 분리했다. 다른 화면은 기존 경로에서 그대로 가져온다.
export { DriverView, PartyChat, PartyShell }

export function PartyScreen(props) {
  if (isPaymentParty(props.party)) return <PaymentParty {...props} />
  return <LegacyParty {...props} />
}

export function PartyDetail({ party, myId, now, onBack }) {
  const mine = party.members.find((m) => m.memberId === myId)
  const toSchool = party.direction === 'TO_SCHOOL'
  const wide = useWide()
  return (
    <Screen aside={mine ? <RouteSide party={party} mine={mine} /> : null}>
      <BackBar onBack={onBack} title={toSchool ? '확정 분담금과 경로' : '일행과 경로'} />
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
        {toSchool && <p className="note">승차 시각 3분 뒤까지 도착 표시가 없으면 그 사람을 빼고 갑니다. 확정 분담금은 남은 일행으로 다시 계산하고, 혼자 남으면 일행이 취소됩니다.</p>}
        <p className="note">{isPaymentParty(party) ? '각자 낼 돈은 확정 분담금 그대로예요. 실제 요금과의 차이는 정산 적립금이 맡아요.' : '실제 요금은 결제 후 같은 비율로 나눕니다. ' + REAL_NAME}</p>
        {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
      </div>
    </Screen>
  )
}

/** 서버가 허용한 본인 답만 전송한다. 두 버튼의 중복 누름도 함께 막는다. bare면 제목 없이 버튼만(할 일 카드 안에서 제목이 겹치지 않게). */
export function RideConfirmationCard({ party, onUpdated, bare = false }) {
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
    {!bare && <><h2>{answer!=null?'내 운행 답변':'운행 확인 중'}</h2><p>일행과 택시에 탔나요? 첫 답변은 바꿀 수 없어요.</p></>}
    {answer!=null ? <Say>내 답변: {answer?'탔어요':'못 탔어요'}</Say>
      : party.canConfirmRide ? <div className="btns"><Btn disabled={busy} onClick={()=>submit(true)}>탔어요</Btn><Btn className="line" disabled={busy} onClick={()=>submit(false)}>못 탔어요</Btn></div>
        : <p>운행 확인 결과를 기다리고 있어요.</p>}
    {error && <p role="alert">{error}</p>}
  </section>
}

export function PartyFareRows({ party }) {
  const increase = party.myConfirmedShare!=null ? party.myShare - party.myConfirmedShare : 0
  return <>
    <Rows items={[
      party.myConfirmedShare!=null ? ['내 확정 분담금',won(party.myConfirmedShare),'big'] : ['내 예상 분담금',won(party.myShare),'big'],
      party.myConfirmedShare!=null && party.myConfirmedShare!==party.myShare && ['지금 예상 분담금',won(party.myShare),'big'],
    ]} />
    {/* 명세 8.4·S24: 확정된 노쇼나 무료 취소 마감 뒤 취소면 늘어난 금액을 빠진 사람의 선결제에서 보전(그 사람 확정 몫까지). */}
    {increase > 0 && <Say title={`일행이 빠져 내 분담금이 ${won(increase)} 늘었어요`}>
      노쇼 확정·마감 후 취소라면 늘어난 금액을 그 사람의 선결제에서 받아 정산 때 내 분담금에서 빼 드려요(그 사람의 확정 분담금까지).
      노쇼가 아니거나 처음 결제자가 빠지면 실제 요금 기준으로 나눠요.
    </Say>}
  </>
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

export function PartyStepper({ party, compact = false }) {
  const ended = ['DISSOLVED', 'NOT_OPERATED'].includes(party.status)
  const current = stepOf(party)
  if (compact) {
    return <div className={`stepper-compact${ended ? ' ended' : ''}`}>
      <div className="stepper-meta">
        <span className="stepper-now">{ended ? partyLabel(party.status) : STEPS[current]}</span>
        <small>{current + 1} / {STEPS.length}</small>
      </div>
      <ol className={`stepper compact${ended ? ' ended' : ''}`} aria-label="지금 단계">
        {STEPS.map((step, i) => <li key={step} className={i < current ? 'done' : i === current ? 'now' : ''}
          aria-current={i === current ? 'step' : undefined}><span aria-hidden="true" /><b className="sr-only">{step}</b></li>)}
      </ol>
    </div>
  }
  return <ol className={`stepper${ended ? ' ended' : ''}`} aria-label="진행 단계">
    {STEPS.map((step, i) => <li key={step} className={i < current ? 'done' : i === current ? 'now' : ''}
      aria-current={i === current ? 'step' : undefined}><span aria-hidden="true" />{step}</li>)}
  </ol>
}

/** 상태와 역할에 맞는 "지금 할 일" 하나. 제목·한 줄 설명·남은 시간과, 필요하면 그 자리에서 누르는 카드. */
function NextAction({ party, now, myId, mine, config, run, onUpdated, onDriver }) {
  // 일행 상태는 모두 결제할 때까지 PREPAYING이라, 내 결제 여부는 내 선결제 주문으로 판단한다.
  const [prepay, setPrepay] = useState(null)
  const waiting = ['PREPAYING', 'READY'].includes(party.status)
  const left = (at) => (at && now ? `${remaining(at, now)} 남음` : '')
  let title
  let detail = ''
  let extra = null
  let tone = ''
  if (party.departureBlockReason === 'ROUTE_VERIFICATION_PENDING') {
    title = '일행 변경 경로를 확인 중이에요'
    detail = `${formatDateTime(party.routeVerificationDeadlineAt)}까지 확인되지 않으면 일행을 해산하고 선결제 환불을 접수해요.`
    tone = 'calm'
  } else if (party.status === 'PAYER_PENDING') {
    const me = party.successorId === myId
    title = me ? (party.payerNickname ? `결제자 ${party.payerNickname}님이 안 왔어요` : '결제자가 안 왔어요') : '새 결제자를 정하고 있어요'
    detail = me
      ? `대신 결제하면 미리 낸 돈은 정산 때 모두 돌려받아요. ${formatTime(party.successionDeadlineAt)}까지 답해 주세요.`
      : '원래 결제자가 오지 않아 다음 사람에게 묻고 있어요.'
    if (me) extra = <div className="btns">
      <Btn onClick={() => run(() => api.payerSuccession(party.id, true), '이제 내가 결제자예요')}>제가 결제할게요</Btn>
      <Btn className="line" onClick={() => run(() => api.payerSuccession(party.id, false), '어렵다고 알렸어요')}>어려워요</Btn>
    </div>
  } else if (party.status === 'RIDE_UNCONFIRMED' || party.canConfirmRide) {
    title = '택시에 탔나요?'
    detail = '운행 시작 표시가 없어 확인하고 있어요. 첫 답변은 바꿀 수 없고, 안 탄 게 확인될 때만 환불해요.'
    extra = <RideConfirmationCard party={party} onUpdated={onUpdated} bare />
  } else if (party.status === 'PREPAYING' && !party.payer) {
    const paid = prepay?.status === 'APPROVED'
    title = paid ? '선결제를 마쳤어요' : prepay?.status === 'PROCESSING' ? '결제를 확인하고 있어요' : '선결제를 마쳐 주세요'
    detail = paid ? '모두 결제하면 만날 시간과 장소를 알려 드려요.'
      : '내 확정 분담금이에요. 실제 요금이 더 나와도 더 내지 않아요.'
    if (paid) tone = 'calm'
    extra = <PaymentCard compact partyId={party.id} myId={myId} now={now} config={config} onUpdated={onUpdated} onOrder={setPrepay} />
  } else if (waiting && party.payer) {
    if (party.calledAt) {
      title = '택시를 불렀어요'
      detail = '기사님께 내릴 순서를 보여 주세요.'
      extra = <Btn className="line" onClick={onDriver}>기사님께 보여 줄 화면</Btn>
    } else {
      // 결제자는 선결제가 없다. 지금 누를 수 없는 호출 버튼은 그리지 않고, 언제 부를 수 있는지를 한 문장으로 알린다.
      // 부를 때가 되면 이 카드가 호출 단계로 바뀐다(카카오T 열기 → 택시 불렀어요).
      const reasons = party.notReadyReasons?.length ? party.notReadyReasons.join(' ') : ''
      if (party.canCall) {
        title = '이제 택시를 불러 주세요'
        detail = party.instant && !party.callReady
          ? '예상 출발 시각이 되어 호출할 수 있어요. 아직 도착하지 않은 일행은 탑승 미확인으로 기록해요.'
          : '카카오T에서 부른 뒤 "택시 불렀어요"를 누르면 일행에게 알려져요.'
        extra = <>
          <KakaoTaxi />
          <Btn onClick={() => run(async () => { await api.callParty(party.id); onDriver() }, '택시를 불렀다고 알렸어요')}>택시 불렀어요</Btn>
        </>
      } else if (party.instant) {
        title = '확정 즉시 승차 지점으로 출발해 주세요'
        detail = reasons || '나는 선결제하지 않아요. 다들 결제하고 도착하면 택시를 부를 수 있어요.'
      } else {
        title = party.status === 'PREPAYING' ? '다른 사람들이 결제하는 중이에요' : `${formatTime(party.callAt || party.departureAt)}쯤 택시를 불러요`
        detail = reasons || '결제자는 선결제가 없어요. 부를 때가 되면 여기서 바로 부를 수 있어요.'
        tone = 'calm'
      }
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
      detail = `${left(goBy)}. 도착하면 "도착했어요"를 눌러 주세요.`
    }
  } else if (party.status === 'AWAITING_FARE') {
    if (party.payer && !party.canSettle) {
      title = '탑승 확인이 끝나면 금액을 입력해요'
      detail = '동승자의 탑승 번호 입력 시간(5분)이 끝나면 실제 요금과 영수증 입력 화면이 열려요.'
      tone = 'calm'
    } else {
      title = party.payer ? '실제 요금을 입력해 주세요' : '결제자의 금액 입력을 기다려요'
      detail = party.payer ? '영수증 사진과 함께 올리면 내 확정 분담금을 뺀 나머지를 받아요.' : '입력되면 24시간 동안 요금 이의를 낼 수 있어요.'
      if (!party.payer) tone = 'calm'
    }
  } else if (['DISSOLVED', 'NOT_OPERATED', 'FARE_MISSING'].includes(party.status)) {
    title = party.status === 'DISSOLVED' ? '일행이 해산됐어요' : '운행이 성사되지 않았어요'
    detail = party.reviewReason === 'ROUTE_VERIFICATION_EXPIRED'
      ? '출발 시각까지 경로를 확인하지 못해 해산했어요. 승인된 선결제는 환불을 접수했어요.'
      : party.reviewReason === 'LATE_CANCELLATION_COMPENSATION'
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
  const wide = useWide()
  const [asking, setAsking] = useState(false)
  const [driver, setDriver] = useState(false)
  const [leavingChange, setLeavingChange] = useState(false)
  const members = party.members || []
  const waiting = ['PREPAYING', 'READY'].includes(party.status)
  const mine = members.find((m) => m.requestId === party.myRequestId)
  const free = party.freeCancelUntil && new Date(now) <= new Date(party.freeCancelUntil)
  const owesPrepay = party.status === 'PREPAYING' && !party.payer
  const canArrive = waiting && !owesPrepay && (party.instant ? !mine?.arrived : party.canArrive)
  // 카드의 굵은 버튼은 하나만: 결제자가 지금 택시를 부를 수 있으면 그게 주 행동이고 도착 표시는 보조 버튼이다.
  const calling = party.payer && waiting && party.canCall && !party.calledAt
  const primary = canArrive
    ? <Btn className={calling ? 'line' : undefined} onClick={() => run(() => (party.instant ? api.prepareParty(party.id, 'ARRIVED') : api.arrive(party.id)), '도착을 표시했어요')}>도착했어요</Btn> : null
  const order = [...members].sort((a, b) => a.rideOrder - b.rideOrder)
  const [people] = usePeople(party.id, party.groupVersion)
  const personOf = (memberId) => people.find((p) => p.memberId === memberId)
  return <>
    <PartyShell party={party} now={now} onUpdated={onUpdated} onMenu={onMenu} onHome={onHome} foot={primary}
      aside={mine ? <RouteSide party={party} mine={mine} /> : null}
      header={<Meter compact label={!knownPartyStatus(party.status) ? partyLabel(party.status) : party.instant ? '출발(예상)' : '출발'} value={formatTime(party.departureAt)} detail={meetPlace(party)} tag={roleTag(party)} />}
      tools={party.payer ? [{ label: '기사님께 보여 주기', onClick: () => setDriver(true) }] : []}
      action={<>
          {/* 지금 어디쯤인지(확정 → 선결제 → 모이기 → 탑승 → 정산)를 카드 맨 위 한 줄로. 자세한 순서는 (i) 일행 정보에 있다.
              선결제할 차례에는 결제 카드에만 집중하게 숨긴다. */}
          {!owesPrepay && <PartyStepper party={party} compact />}
          <NextAction party={party} now={now} myId={myId} mine={mine} config={config} run={run} onUpdated={onUpdated} onDriver={() => setDriver(true)} />
          {['READY', 'RIDE_UNCONFIRMED', 'AWAITING_FARE'].includes(party.status) && <BoardingCodePanel party={party} now={now} onUpdated={onUpdated} demo={!!config?.demo} />}
          {party.canLeaveAfterChange && <Say tone="stop-tone" title="일행이 바뀌었어요"
            action={<Btn className="small line" onClick={() => setLeavingChange(true)}>변경돼서 빠지기</Btn>}>
            현재 내 확정 분담금은 {won(party.myShare)}이에요. 원하지 않으면 {party.changeExitFree ? '호출 전까지 무료로' : ''} 빠질 수 있어요.
          </Say>}
          {waiting && party.instant && !mine?.arrived && !owesPrepay && <Btn className="line" disabled={mine?.nearby}
            onClick={() => run(() => api.prepareParty(party.id, 'NEARBY'), '2분 안 도착으로 표시했어요')}>{mine?.nearby ? '2분 안 도착 표시 완료' : '2분 안 도착'}</Btn>}
      </>}
      progress={<>
        {/* 일행 정보 창 순서: 지금 어디쯤 → 누구와 어디서 내리나 → 얼마 내나 → 왜 이 일행인가 → 지도 */}
        <div className="block">
          <PartyStepper party={party} />
          <h2 className="block-title">같이 타는 사람 <small>내리는 순서</small></h2>
          <Rail items={order.map((m) => ({
            key: m.requestId,
            pin: m.rideOrder,
            avatar: <Avatar name={m.nickname} />,
            name: m.memberId === myId ? m.nickname + ' (나)' : m.nickname,
            badge: m.memberId === party.payerId ? '택시 부름' : '',
            detail: [m.pointName, m.expectedShare != null ? won(m.expectedShare) : ''].filter(Boolean).join(', '),
            sub: m.memberId === myId ? '' : trustLine(personOf(m.memberId)),
            me: m.memberId === myId,
            state: party.status === 'PREPAYING' && m.memberId !== party.payerId && typeof m.prepaid === 'boolean'
              ? (m.prepaid ? '결제 완료' : '결제 전')
              : waiting ? (m.noShow ? '불참' : m.arrived ? '도착' : m.nearby ? '2분 안' : '오는 중') : '',
            ok: party.status === 'PREPAYING' ? m.prepaid === true : m.arrived,
          }))} />
          {party.payer && <div className="list"><Item title="기사님께 보여 줄 화면" onClick={() => setDriver(true)} /></div>}
        </div>
        <div className="block">
          {party.mySoloFare > party.myShare && <FareCompare solo={party.mySoloFare} share={party.myShare} />}
          {mine && party.mySoloFare > 0 && <FairnessNote delay={figures(mine).delay} solo={party.mySoloFare} share={party.myShare}
            rules={config?.rules} walkExcluded={isPaymentParty(party)} />}
          {/* 넓은 화면은 오른쪽 지도가 경로를 크게 보여 주므로 작은 지도는 휴대폰에만 둔다. */}
          {!wide && mine && <PartyMiniMap party={party} mine={mine} />}
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
        <Fold title="규칙 자세히" className="rules">
          <ul>
            <li>{free ? '무료 취소 마감 전 취소하면 승인된 선결제를 전액 환불해요.' : '무료 취소 마감이 지나 취소하면 남은 일행의 늘어난 금액을 보전해요.'}</li>
            <li>누가 오지 않으면 일행·본인 소명을 보고 24시간 뒤 판정해요. 노쇼로 확정되면 남은 일행의 늘어난 금액을 그 사람의 선결제에서 보전하고, 판단할 수 없으면 노쇼로 보지 않아요.</li>
            <li>선결제는 확정 때 정해진 확정 분담금 그대로이고, 그게 최종 금액이에요. 길이 막혀 요금이 더 나와도 더 받지 않아요. 차이는 정산 적립금이 맡아요.</li>
            {party.instant && <li>즉시 일행은 선결제 기한이 확정 뒤 {config?.instantMatching?.prepMinutes ?? 5}분이고, 모두 준비되면 바로 호출해요.</li>}
            <li>택시는 결제자가 카카오T로 직접 불러요. 앱은 부른 사실만 기록해요.</li>
            <li>운행 시작 표시가 없으면 탔는지 물어보고, 미운행이 확인될 때만 환불해요.</li>
            <li>{CHAT_RETENTION}</li>
          </ul>
        </Fold>
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
