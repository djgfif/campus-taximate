import React, { useCallback, useEffect, useRef, useState } from 'react'
import { Check, Copy, X } from 'lucide-react'
import RouteComparison, { RouteSide } from './RouteComparison.jsx'
import { api } from './api.js'
import { subscribeChatUpdates } from './chatEvents.js'
import { MemberActions, ReportSheet } from './MemberSafety.jsx'
import { formatTime, remaining, won } from './format.js'
import { BackBar, Btn, Confirm, Item, Meter, Rail, Rows, Say, Screen, TopBar, toast, useWide } from './ui.jsx'

const CHAT_PHRASES = ['조금 늦어요', '도착했어요, 어디 계세요?', '인상착의: ', '택시 잡았어요']

/**
 * Chat owns its own message cursor. SSE tells it when to fetch; polling and focus
 * recovery cover missed events and disconnected browsers. The server is the source of truth.
 */
export function PartyChat({ partyId, status }) {
  const [chat, setChat] = useState(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [denied, setDenied] = useState(false)
  const [selected, setSelected] = useState(null)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [unread, setUnread] = useState(0)
  const input = useRef(null)
  const scroll = useRef(null)
  const sending = useRef(false)
  const pending = useRef(null)
  const syncing = useRef(false)
  const syncAgain = useRef(false)
  const disposed = useRef(false)
  const newestId = useRef(0)
  const oldestId = useRef(null)
  const atBottom = useRef(true)

  const synchronize = useCallback(async () => {
    if (syncing.current) { syncAgain.current = true; return }
    syncing.current = true
    try {
      do {
        syncAgain.current = false
        const startCursor = newestId.current
        let cursor = startCursor
        const received = []
        let response
        try {
          let pages = 0
          do {
            response = await api.partyMessages(partyId, cursor || null)
            if (disposed.current) return
            received.push(...response.messages)
            if (response.messages.length) cursor = response.messages[response.messages.length - 1].id
            pages += 1
            // A large offline backlog is drained through subsequent synchronization rounds.
            if (pages >= 20 && response.hasNewer) {
              syncAgain.current = true
              break
            }
          } while (response.hasNewer && response.messages.length)
          if (disposed.current) return
          newestId.current = Math.max(newestId.current, cursor)
          if (startCursor > 0 && !atBottom.current) {
            const unseen = received.filter((message) => !message.mine).length
            if (unseen) setUnread((count) => count + unseen)
          }
          setChat((previous) => {
            if (!previous || startCursor === 0) {
              if (received.length) oldestId.current = received[0].id
              return { ...response, messages: received }
            }
            const known = new Set(previous.messages.map((message) => message.id))
            const additions = received.filter((message) => !known.has(message.id))
            return { ...previous, closed: response.closed, closesAt: response.closesAt,
              messages: [...previous.messages, ...additions] }
          })
          setError('')
          setDenied(false)
        } catch (e) {
          if (disposed.current) return
          setError(e.message)
          if (e.status === 403) {
            setChat(null); setDenied(true); setSelected(null); setText('')
          }
          break
        }
      } while (syncAgain.current && !disposed.current)
    } finally {
      syncing.current = false
    }
  }, [partyId])

  useEffect(() => {
    disposed.current = false
    synchronize()
    const unsubscribe = subscribeChatUpdates((changedPartyId) => {
      if (changedPartyId == null || changedPartyId === partyId) synchronize()
    })
    // SSE is best effort. Reconcile periodically and after tab resume.
    const timer = setInterval(synchronize, 15000)
    const wake = () => { if (document.visibilityState === 'visible') synchronize() }
    document.addEventListener('visibilitychange', wake)
    return () => {
      disposed.current = true
      unsubscribe()
      clearInterval(timer)
      document.removeEventListener('visibilitychange', wake)
    }
  }, [partyId, synchronize])

  useEffect(() => { if (chat) synchronize() }, [status]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const node = scroll.current
    if (node && atBottom.current) {
      node.scrollTop = node.scrollHeight
      setUnread(0)
    }
  }, [chat?.messages.length])

  const onScroll = () => {
    const node = scroll.current
    if (!node) return
    atBottom.current = node.scrollHeight - node.scrollTop - node.clientHeight < 48
    if (atBottom.current) setUnread(0)
  }

  const loadOlder = async () => {
    if (loadingOlder || !chat?.hasOlder || oldestId.current == null) return
    setLoadingOlder(true)
    setError('')
    const node = scroll.current
    const previousHeight = node?.scrollHeight || 0
    const previousTop = node?.scrollTop || 0
    try {
      const response = await api.partyMessages(partyId, null, oldestId.current)
      if (disposed.current) return
      if (response.messages.length) oldestId.current = response.messages[0].id
      atBottom.current = false
      setChat((previous) => {
        if (!previous) return previous
        const known = new Set(previous.messages.map((message) => message.id))
        return { ...previous, hasOlder: response.hasOlder,
          messages: [...response.messages.filter((message) => !known.has(message.id)), ...previous.messages] }
      })
      requestAnimationFrame(() => {
        if (node && !disposed.current) node.scrollTop = node.scrollHeight - previousHeight + previousTop
      })
    } catch (e) {
      setError(e.message)
    } finally {
      setLoadingOlder(false)
    }
  }

  const send = async (value, clearDraft = true) => {
    const message = value.trim()
    if (sending.current || !message) return
    sending.current = true
    setBusy(true)
    setError('')
    if (!pending.current || pending.current.text !== message) {
      const id = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`
      pending.current = { text: message, id }
    }
    try {
      await api.sendPartyMessage(partyId, message, pending.current.id)
      pending.current = null
      if (clearDraft) setText('')
      await synchronize()
    } catch (e) {
      setError(e.message)
      if (e.code === 'CHAT_CLOSED') setChat((previous) => previous && { ...previous, closed: true })
      if (e.status === 403) { setChat(null); setDenied(true); setSelected(null); setText('') }
    } finally {
      sending.current = false
      setBusy(false)
    }
  }

  const report = async (message) => {
    try {
      const peers = await api.partyMembers(partyId)
      const member = peers.find((peer) => peer.memberId === message.memberId)
      if (member) setSelected({ ...member, messageId: message.id })
    } catch (e) { setError(e.message) }
  }

  const closed = chat?.closed || ['COMPLETED', 'SETTLED', 'CANCELED'].includes(status)
  return (
    <section className="block party-chat" aria-label="일행 채팅">
      <h2 className="block-title">일행 채팅</h2>
      {!chat && !error && <p role="status">메시지를 불러오고 있어요</p>}
      {chat && (
        <>
          {chat.hasOlder && <Btn className="small line" disabled={loadingOlder} onClick={loadOlder}>이전 메시지 보기</Btn>}
          <ol ref={scroll} onScroll={onScroll} className="chat-messages" aria-label="최근 일행 메시지" aria-live="polite" aria-relevant="additions">
            {chat.messages.map((message) => (
              <li key={message.id} className={message.mine ? 'mine' : ''}>
                <div className="chat-meta"><b>{message.mine ? `나 (${message.nickname})` : message.nickname}</b>
                  <time dateTime={message.createdAt}>{formatTime(message.createdAt)}</time>
                  {!message.mine && <Btn className="small quiet" aria-label={`${message.nickname}님 메시지 신고`} onClick={() => report(message)}>신고</Btn>}
                </div>
                <p>{message.text}</p>
              </li>
            ))}
          </ol>
          {unread > 0 && <Btn className="small line" onClick={() => {
            const node = scroll.current
            if (node) node.scrollTop = node.scrollHeight
            atBottom.current = true
            setUnread(0)
          }}>새 메시지 {unread}개 · 아래로 이동</Btn>}
          {chat.messages.length === 0 && <p className="note">아직 메시지가 없어요.</p>}
        </>
      )}
      {closed && !denied && <p className="note" role="status">일행 채팅은 읽기만 가능해요.</p>}
      {!denied && chat && !closed && (
        <form onSubmit={(event) => { event.preventDefault(); send(text) }}>
          <div className="chat-phrases" role="group" aria-label="자주 쓰는 문구">
            {CHAT_PHRASES.map((phrase) => <Btn key={phrase} className="small line" disabled={busy} onClick={() => {
              if (phrase === '인상착의: ') {
                setText(phrase)
                input.current?.focus()
              } else return send(phrase, false)
            }}>{phrase}</Btn>)}
          </div>
          <label className="field">일행에게 보낼 메시지
            <textarea ref={input} maxLength={200} value={text} disabled={busy} onChange={(e) => setText(e.target.value)} />
          </label>
          <Btn type="submit" disabled={busy || !text.trim()}>메시지 보내기</Btn>
        </form>
      )}
      {error && <p role="alert">{error} <button type="button" onClick={synchronize}>다시 불러오기</button></p>}
      <p className="note">정산이 끝나거나 출발 후 24시간이 지나면 쓰기가 닫혀요. 그때부터 7일 뒤 메시지를 지워요.</p>
      {selected && <ReportSheet partyId={partyId} member={selected} onClose={() => setSelected(null)} onSaved={() => setSelected(null)} />}
    </section>
  )
}

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
        <div className="list">
          {party.payer && <Item title="기사님께 보여 줄 화면" detail="내릴 곳을 큰 글씨로 보여 드려요" onClick={() => setDriver(true)} />}
          <Item title="일행과 경로 자세히" onClick={onDetail} />
        </div>
      </div>
      <PartyChat key={party.id} partyId={party.id} now={now} status={party.status} />
      {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
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
      {asking && <CancelSheet party={party} run={run} onClose={() => setAsking(false)} />}
    </Screen>
  )
}

export function PartyScreen(props) {
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
        <PartyChat key={party.id} partyId={party.id} now={now} status={party.status} />
      {new Date(now) >= new Date(party.departureAt) && <MemberActions partyId={party.id} />}
      </div>
    </Screen>
  )
}
