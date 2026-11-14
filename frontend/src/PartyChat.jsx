import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ImagePlus, RotateCw, Send, X } from 'lucide-react'
import { api } from './api.js'
import { ReportSheet } from './MemberSafety.jsx'
import { formatTime } from './format.js'
import { Avatar, Btn } from './ui.jsx'

const CHAT_PHRASES = ['어디쯤이세요?', '저 5분 정도 늦어요 ㅠㅠ', '도착했어요!', '저는 ', '택시 잡았어요']
export const CHAT_RETENTION = '정산이 끝나거나 출발 후 24시간이 지나면 쓰기가 닫혀요. 그때부터 7일 뒤 메시지와 사진을 지워요.'
const GROUP_GAP_MS = 5 * 60 * 1000
const NEAR_BOTTOM_PX = 48
const INPUT_MAX_PX = 120
const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const IMAGE_MAX_BYTES = 5 * 1024 * 1024

/** 휴대폰처럼 손가락으로 쓰는 화면에서는 Enter를 줄바꿈으로 둔다. */
const coarsePointer = () => typeof window !== 'undefined' && !!window.matchMedia?.('(pointer: coarse)')?.matches
const newClientId = () => {
  const raw = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return raw.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 64)
}
const dayKey = (iso) => new Date(iso).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })
const dayLabel = (iso) => new Date(iso).toLocaleDateString('ko-KR', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: 'long', day: 'numeric', weekday: 'short',
})

/** 서버 메시지를 번호 순으로 합친다. 같은 번호는 새 값(안 읽은 수 갱신)으로 바꾼다. */
function mergeById(current, incoming) {
  const byId = new Map(current.map((m) => [m.id, m]))
  for (const m of incoming) if (m && m.id != null) byId.set(m.id, m)
  return [...byId.values()].sort((a, b) => a.id - b.id)
}

/**
 * 일행 채팅. 서버의 chat 신호(taximate:chat)나 상태 갱신(now)이 오면 최근 메시지를 다시 읽고,
 * 보낸 메시지는 바로 보이게 하고(보내는 중/실패 다시 보내기), 맨 아래를 보고 있으면 읽음을 알린다.
 * 이용 내역처럼 now가 없으면 5초마다 읽는다.
 */
export function PartyChat({ partyId, now, status, active = true, full = false }) {
  const [items, setItems] = useState([])
  const [pending, setPending] = useState([])
  const [meta, setMeta] = useState(null)
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const [denied, setDenied] = useState(false)
  const [selected, setSelected] = useState(null)
  const [unseen, setUnseen] = useState(false)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const input = useRef(null)
  const fileInput = useRef(null)
  const list = useRef(null)
  const pinned = useRef(true)
  const readSent = useRef(0)
  const readVersion = useRef(0)
  const keepScroll = useRef(null)
  const lastSeenId = useRef(0)

  const deny = useCallback((message) => {
    setItems([]); setPending([]); setMeta(null); setDenied(true); setSelected(null); setText('')
    if (message) setError(message)
  }, [])

  const loadLatest = useCallback(async () => {
    const version = ++readVersion.current
    try {
      const next = await api.partyMessages(partyId)
      if (version !== readVersion.current) return
      setItems((current) => mergeById(current, next.messages || []))
      setMeta((current) => ({ closed: !!next.closed, hasOlder: current?.loadedOlder ? current.hasOlder : !!next.hasOlder, loadedOlder: current?.loadedOlder }))
      setDenied(false); setError('')
    } catch (e) {
      if (version !== readVersion.current) return
      setError(e.message)
      if (e.status === 403) deny()
    }
  }, [partyId, deny])

  // 상태 갱신·채팅 신호·이용 내역 주기 읽기
  useEffect(() => {
    loadLatest()
    const timer = now == null ? setInterval(loadLatest, 5000) : null
    return () => clearInterval(timer)
  }, [loadLatest, now, status])
  useEffect(() => {
    const onChat = (event) => { if (Number(event.detail) === Number(partyId)) loadLatest() }
    window.addEventListener('taximate:chat', onChat)
    return () => window.removeEventListener('taximate:chat', onChat)
  }, [partyId, loadLatest])

  const lastServerId = items.length ? items[items.length - 1].id : 0
  const lastMine = pending.length > 0 || (items.length > 0 && items[items.length - 1].mine)

  // 맨 아래를 보고 있거나 내가 보냈으면 따라가고, 위를 읽는 중이면 "새 메시지" 버튼을 띄운다.
  useLayoutEffect(() => {
    const el = list.current
    if (!el) return
    if (keepScroll.current != null) {
      el.scrollTop = el.scrollHeight - keepScroll.current
      keepScroll.current = null
      return
    }
    const grew = lastServerId > lastSeenId.current || pending.length > 0
    lastSeenId.current = Math.max(lastSeenId.current, lastServerId)
    if (pinned.current || lastMine) {
      el.scrollTop = el.scrollHeight
      pinned.current = true
      setUnseen(false)
    } else if (grew) {
      setUnseen(true)
    }
  }, [lastServerId, pending.length, lastMine])

  // 보이는 화면에서 맨 아래까지 봤으면 읽음을 알린다(앞으로만).
  const reportRead = useCallback(() => {
    // 탭에 가려져 있으면 읽은 것으로 보지 않는다.
    if (!active || !lastServerId || lastServerId <= readSent.current || !pinned.current) return
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    readSent.current = lastServerId
    api.readPartyMessages?.(partyId, lastServerId)?.catch?.(() => { readSent.current = 0 })
  }, [partyId, lastServerId, active])
  useEffect(() => { reportRead() }, [reportRead])
  useEffect(() => {
    const wake = () => reportRead()
    document.addEventListener('visibilitychange', wake)
    return () => document.removeEventListener('visibilitychange', wake)
  }, [reportRead])

  // 입력창 높이를 글자 수에 맞춘다.
  useEffect(() => {
    const el = input.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, INPUT_MAX_PX)}px`
  }, [text])

  const onScroll = () => {
    const el = list.current
    pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX
    if (pinned.current) { setUnseen(false); reportRead() }
  }
  const toBottom = () => {
    const el = list.current
    if (!el) return
    el.scrollTop = el.scrollHeight
    pinned.current = true
    setUnseen(false)
    reportRead()
  }

  const loadOlder = async () => {
    if (!items.length || loadingOlder) return
    setLoadingOlder(true)
    try {
      const older = await api.partyMessages(partyId, null, items[0].id)
      const el = list.current
      if (el) keepScroll.current = el.scrollHeight - el.scrollTop
      setItems((current) => mergeById(current, older.messages || []))
      setMeta((current) => ({ ...current, hasOlder: !!older.hasOlder, loadedOlder: true }))
    } catch (e) { setError(e.message) } finally { setLoadingOlder(false) }
  }

  const fail = (clientId, e) => {
    setPending((list) => list.map((p) => (p.clientId === clientId ? { ...p, state: 'failed' } : p)))
    setError(e.message)
    if (e.code === 'CHAT_CLOSED') setMeta((value) => value && { ...value, closed: true })
    if (e.status === 403) deny(e.message)
  }
  const deliver = async (item) => {
    setPending((list) => list.map((p) => (p.clientId === item.clientId ? { ...p, state: 'sending' } : p)))
    try {
      const saved = item.kind === 'IMAGE'
        ? await api.sendPartyImage(partyId, item.file, item.clientId)
        : await api.sendPartyMessage(partyId, item.text, item.clientId)
      setPending((list) => list.filter((p) => p.clientId !== item.clientId))
      if (item.preview) URL.revokeObjectURL?.(item.preview)
      setItems((current) => mergeById(current, [saved]))
      setError('')
    } catch (e) { fail(item.clientId, e) }
  }
  const sendText = (value, clearDraft = true) => {
    const trimmed = value.trim()
    if (!trimmed) return
    const item = { clientId: newClientId(), kind: 'TEXT', text: trimmed, createdAt: new Date().toISOString(), state: 'sending' }
    setPending((list) => [...list, item])
    if (clearDraft) setText('')
    pinned.current = true
    deliver(item)
  }
  const sendImage = (file) => {
    if (!file) return
    if (!IMAGE_TYPES.includes(file.type)) { setError('JPG, PNG, WEBP 사진만 보낼 수 있어요.'); return }
    if (file.size > IMAGE_MAX_BYTES) { setError('사진은 5MB 이하만 보낼 수 있어요.'); return }
    const preview = URL.createObjectURL?.(file)
    const item = { clientId: newClientId(), kind: 'IMAGE', file, preview, text: '', createdAt: new Date().toISOString(), state: 'sending' }
    setPending((list) => [...list, item])
    pinned.current = true
    deliver(item)
  }
  const discard = (item) => {
    setPending((list) => list.filter((p) => p.clientId !== item.clientId))
    if (item.preview) URL.revokeObjectURL?.(item.preview)
  }
  const report = async (message) => {
    try {
      const peers = await api.partyMembers(partyId)
      const member = peers.find((peer) => peer.memberId === message.memberId)
      if (member) setSelected(member)
    } catch (e) { setError(e.message) }
  }
  const closed = meta?.closed || ['COMPLETED', 'CANCELED', 'DISSOLVED', 'NOT_OPERATED', 'FARE_MISSING'].includes(status)

  const rows = [...items, ...pending.map((p) => ({ ...p, id: `p-${p.clientId}`, mine: true, pending: true }))]
  const rendered = []
  rows.forEach((message, i) => {
    const prev = rows[i - 1]
    if (!prev || dayKey(prev.createdAt) !== dayKey(message.createdAt)) {
      rendered.push(<li key={`d-${message.id}`} className="chat-day" role="separator"><span>{dayLabel(message.createdAt)}</span></li>)
    }
    if (message.kind === 'SYSTEM') {
      rendered.push(<li key={message.id} className="chat-system"><span>{message.text}</span></li>)
      return
    }
    const head = !prev || prev.kind === 'SYSTEM' || prev.mine !== message.mine || prev.memberId !== message.memberId
      || new Date(message.createdAt) - new Date(prev.createdAt) > GROUP_GAP_MS
    const info = <span className="chat-meta">
      {message.pending
        ? <span className="chat-state">{message.state === 'failed' ? '전송 실패' : '보내는 중'}</span>
        : <>
          {message.unread > 0 && <b className="chat-unread" aria-label={`안 읽은 사람 ${message.unread}명`}>{message.unread}</b>}
          <time dateTime={message.createdAt}>{formatTime(message.createdAt)}</time>
        </>}
    </span>
    const imageSrc = message.pending ? message.preview : message.imageUrl
    const bubble = message.kind === 'IMAGE'
      ? (imageSrc
        ? (message.pending
          ? <img className="chat-image" src={imageSrc} alt="보내는 중인 사진" />
          : <a className="chat-image-link" href={imageSrc} target="_blank" rel="noreferrer">
            <img className="chat-image" src={imageSrc} alt={message.mine ? '내가 보낸 사진' : `${message.nickname}님이 보낸 사진`} loading="lazy" />
          </a>)
        : <p className="bubble">사진</p>)
      : <p className="bubble">{message.text}</p>
    rendered.push(
      <li key={message.id} className={`${message.mine ? 'mine' : 'theirs'}${head ? ' chat-head' : ''}${message.pending ? ' chat-pending' : ''}`}>
        {head && !message.mine && <Avatar name={message.nickname} size={34} />}
        {head && !message.mine && <b className="chat-name">{message.nickname}</b>}
        <div className="chat-line">
          {message.mine && info}
          {bubble}
          {!message.mine && info}
          {!message.mine && !message.pending && (
            <button type="button" className="chat-report" aria-label={`${message.nickname}님 메시지 신고`}
              onClick={() => report(message)}>신고</button>
          )}
        </div>
        {message.pending && message.state === 'failed' && (
          <div className="chat-retry">
            <button type="button" onClick={() => deliver(message)} aria-label="다시 보내기"><RotateCw size={14} aria-hidden="true" /> 다시 보내기</button>
            <button type="button" onClick={() => discard(message)} aria-label="보내지 않고 지우기"><X size={14} aria-hidden="true" /> 지우기</button>
          </div>
        )}
      </li>,
    )
  })

  return (
    <section className={full ? 'party-chat full' : 'block party-chat'} aria-label="일행 채팅">
      <h2 className={full ? 'sr-only' : 'block-title'}>일행 채팅</h2>
      {!meta && !error && !denied && <p role="status">메시지를 불러오고 있어요</p>}
      {meta && !denied && (
        <div className="chat-box">
          {meta.hasOlder && (
            <button type="button" className="chat-older" onClick={loadOlder} disabled={loadingOlder}>
              {loadingOlder ? '불러오는 중' : '이전 메시지 보기'}
            </button>
          )}
          {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- 스크롤되는 목록이라 키보드로 포커스해 읽을 수 있어야 한다 */}
          <ol ref={list} className="chat-messages" tabIndex={0} onScroll={onScroll}
            aria-label="최근 일행 메시지" aria-live="polite" aria-relevant="additions">
            {rows.length === 0 && <li className="chat-empty">아직 조용하네요. 먼저 인사해 볼까요?</li>}
            {rendered}
          </ol>
          {unseen && <button type="button" className="chat-new" onClick={toBottom}>새 메시지 ↓</button>}
        </div>
      )}
      {closed && !denied && <p className="note" role="status">일행 채팅은 읽기만 가능해요.</p>}
      {!denied && meta && !closed && (
        <form className="chat-form" onSubmit={(event) => { event.preventDefault(); sendText(text) }}>
          <div className="chat-phrases" role="group" aria-label="자주 쓰는 문구">
            {CHAT_PHRASES.map((phrase) => <Btn key={phrase} className="small line" onClick={() => {
              if (phrase === '저는 ') {
                setText(phrase)
                input.current?.focus()
              } else sendText(phrase, false)
            }}>{phrase === '저는 ' ? '내 옷차림 알리기' : phrase}</Btn>)}
          </div>
          <div className="chat-compose">
            <button type="button" className="chat-attach" aria-label="사진 보내기" onClick={() => fileInput.current?.click()}>
              <ImagePlus size={20} aria-hidden="true" />
            </button>
            <input ref={fileInput} type="file" accept={IMAGE_TYPES.join(',')} hidden aria-hidden="true" tabIndex={-1}
              onChange={(e) => { sendImage(e.target.files?.[0]); e.target.value = '' }} />
            <textarea ref={input} rows={1} maxLength={200} value={text}
              aria-label="일행에게 보낼 메시지" placeholder="메시지 보내기"
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                // 한글 조합 중 Enter는 글자 확정이고, 휴대폰에서는 Enter가 줄바꿈이다.
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && !coarsePointer()) {
                  e.preventDefault()
                  sendText(text)
                }
              }} />
            <button type="submit" className="chat-send" aria-label="메시지 보내기" disabled={!text.trim()}>
              <Send size={20} aria-hidden="true" />
            </button>
          </div>
          {text.length >= 160 && <small className="chat-count">{text.length}/200</small>}
        </form>
      )}
      {error && <p role="alert">{error} {!denied && <button type="button" onClick={loadLatest}>다시 불러오기</button>}</p>}
      {!full && <p className="note">{CHAT_RETENTION}</p>}
      {selected && <ReportSheet partyId={partyId} member={selected} onClose={() => setSelected(null)} onSaved={() => setSelected(null)} />}
    </section>
  )
}

export default PartyChat
