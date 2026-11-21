import React, { useCallback, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Copy, Info, X } from 'lucide-react'
import { api } from './api.js'
import { PartyChat } from './PartyChat.jsx'
import { BoardingPanel } from './ReviewScreens.jsx'
import { formatDateTime, formatTime, isPaymentParty, partyLabel, won } from './format.js'
import { Btn, Confirm, Screen, toast, useDialog, useWide } from './ui.jsx'

export const REAL_NAME = '송금할 때 서로의 실명이 표시될 수 있습니다.'
export const roleTag = (party) => (party.payer ? '내가 결제자' : `결제는 ${party.payerNickname}님`)

/** 일행 화면의 보조 동작. 홈은 위쪽 뒤로 버튼이 맡고, 여기는 정보 탭 맨 아래의 취소만 둔다. */
export function PartySecondary({ party, onCancel }) {
  if (!party.canCancel) return null
  return <section className="secondary" aria-label="일행 보조 메뉴">
    <Btn className="quiet" onClick={onCancel}>내 탑승 취소</Btn>
  </section>
}

export function CancelSheet({ party, now, run, onClose }) {
  return (
    <Confirm title="탑승을 취소할까요?" action="탑승 취소" onClose={onClose}
      onConfirm={() => run(() => api.cancelRequest(party.myRequestId), '탑승을 취소했어요')}>
      {party.freeCancelUntil && <span>{formatDateTime(party.freeCancelUntil)}까지 무료 취소예요.
        {now && new Date(now) <= new Date(party.freeCancelUntil) ? ' 선결제했으면 전액 환불해요.' : ' 무료 취소 마감이 지났어요.'}<br /></span>}
      남은 일행의 확정 분담금을 다시 계산하고, 한 명만 남으면 해산해요.
    </Confirm>
  )
}

/** 결제자가 기사님께 보여 주는 화면. 일반호출은 경유를 넣을 수 없어 들를 곳을 순서대로 크게 보여 준다. */
export function DriverView({ party, onClose }) {
  const box = useRef(null)
  useDialog(box, onClose)
  const toSchool = party.direction === 'TO_SCHOOL'
  const stops = party.members.filter((m) => !m.noShow).sort((a, b) => a.rideOrder - b.rideOrder)
  const pickups = toSchool ? stops.filter((m) => m.memberId !== party.payerId) : stops
  const callTo = pickups[0]?.pointName || party.anchorName
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(callTo)
      toast('호출 목적지를 복사했어요')
    } catch {
      toast('복사하지 못했습니다. 직접 입력해 주세요.', 'bad')
    }
  }
  return (
    <div ref={box} tabIndex={-1} className="driver" role="dialog" aria-modal="true" aria-label="기사님께 보여 줄 화면">
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

/** 휴대폰에서 일행 정보(진행 단계 아래 내용·규칙·취소)를 화면 전체로 연다. 뒤로·Esc로 닫는다. */
export function RoomSheet({ title, onClose, children }) {
  const box = useRef(null)
  useDialog(box, onClose)
  return (
    <div ref={box} tabIndex={-1} className="room-sheet" role="dialog" aria-modal="true" aria-label={title}>
      <header className="bar back">
        <div className="bar-left">
          <button type="button" className="icon-btn" aria-label="닫기" onClick={onClose}><ChevronLeft size={26} /></button>
          <span className="bar-title">{title}</span>
        </div>
      </header>
      <div className="room-sheet-body">{children}</div>
    </div>
  )
}

/** 방 머리 첫 줄: 모이는 곳과 시각. 거점 이름까지 붙인 전체 이름은 일행 정보 창에 있다. */
const roomTitle = (party, at) => [party.meetingPoint || party.anchorName, at ? formatTime(at) : ''].filter(Boolean).join(', ')

/**
 * 모든 일행 화면의 뼈대. 일행은 채팅방이다.
 * 머리는 두 줄(모이는 곳과 시각 / 인원과 상태, 내 몫)만 두고, 나머지는 채팅에 양보한다.
 * 지금 답해야 하는 일(action, foot)은 입력창 바로 위 카드로 붙여 채팅을 가리지 않는다.
 * 진행 단계, 같이 타는 사람, 규칙(header, progress, info)은 (i)로 여는 일행 정보 창에 모은다.
 * 넓은 화면은 같은 방을 왼쪽 판에 두고 오른쪽을 지도(aside)로 채운다.
 * summary는 둘째 줄 상태 문구(없으면 일행 상태 이름), time은 첫째 줄 시각(없으면 출발 시각)이다.
 */
/**
 * 지금 할 일이 바뀌었는지 가리는 열쇠. 상태가 그대로여도 서버가 새 행동을 열어 주면(예: 호출 가능, 도착 가능,
 * 탑승 확인 질문, 결제자 승계) 할 일 카드 내용이 바뀐다. 접어 둔 카드는 이 값이 바뀔 때 다시 펼친다.
 * 남은 시간처럼 매초 바뀌는 값은 넣지 않는다(넣으면 접어도 바로 다시 펼쳐진다).
 */
export const taskKeyOf = (party) => [party.status, party.canCall, party.callReady, party.calledAt, party.canArrive, party.canBoard,
  party.canConfirmRide, party.canSettle, party.canLeaveAfterChange, party.departureBlockReason, party.successorId,
  party.adminReviewRequired, party.reviewReason].map((v) => (v == null ? '' : String(v))).join('|')

export function PartyShell({ party, now, onHome, header, action, progress, info, foot, aside, tools = [], summary, time }) {
  const wide = useWide()
  const [details, setDetails] = useState(false)
  const openDetails = useCallback(() => setDetails(true), [])
  const closeDetails = useCallback(() => setDetails(false), [])
  const menu = [...tools, { label: '일행 정보와 규칙', onClick: openDetails }]
  const present = (party.members || []).filter((m) => !m.noShow)
  const people = present.length
  // 선결제 중에는 누가 냈는지가 가장 궁금하다. 결제자는 선결제가 없으니 세지 않는다. 서버가 값을 주지 않으면 상태 이름을 쓴다.
  const payers = present.filter((m) => m.memberId !== party.payerId)
  const prepayLine = party.status === 'PREPAYING' && payers.length > 0 && payers.every((m) => typeof m.prepaid === 'boolean')
    ? `선결제 ${payers.filter((m) => m.prepaid).length}/${payers.length}명 완료` : null
  const settled = Boolean(party.settlement)
  const task = action || foot
    ? <div className="task-card">{action}{foot && <div className="task-foot">{foot}</div>}</div>
    : null
  const taskKey = taskKeyOf(party)
  const room = (
    <div className="room">
      <header className="room-head">
        <div className="room-line">
          <button type="button" className="icon-btn" aria-label="홈으로" onClick={onHome}><ChevronLeft size={26} /></button>
          <h1 className="room-title">{roomTitle(party, time ?? party.departureAt)}</h1>
          <button type="button" className="icon-btn" aria-label="일행 정보 열기" onClick={openDetails}><Info size={22} /></button>
        </div>
        <div className="room-sub">
          <button type="button" className="room-meta-btn" onClick={openDetails}>
            <span>{people}명, {prepayLine || summary || partyLabel(party.status)}</span>
            <ChevronRight size={14} aria-hidden="true" />
          </button>
          {party.myShare != null && <span className="room-share">{settled || isPaymentParty(party) ? '확정 분담금' : '예상 분담금'} <b>{won(party.myShare)}</b>
            {party.mySoloFare > party.myShare && <s aria-label={`혼자 타면 ${won(party.mySoloFare)}`}>{won(party.mySoloFare)}</s>}</span>}
        </div>
      </header>
      <section className="chat-pane" aria-label="채팅 창">
        <PartyChat key={party.id} partyId={party.id} now={now} status={party.status} active full tools={menu} task={task} taskKey={taskKey} />
      </section>
      {details && <RoomSheet title="일행 정보" onClose={closeDetails}>
        {header}
        {progress && <section className="party-panel" aria-label="진행">{progress}</section>}
        <section className="party-panel party-info" aria-label="일행 정보">
          <h2 className="section-title">일행 정보</h2>
          {info}
          <BoardingPanel party={party} now={now} />
        </section>
      </RoomSheet>}
    </div>
  )
  return wide ? <Screen aside={aside}>{room}</Screen> : room
}
