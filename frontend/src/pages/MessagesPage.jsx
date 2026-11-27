import React, { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, MessageCircle } from 'lucide-react'
import { api } from '../api.js'
import { PartyChat } from '../PartyChat.jsx'
import { formatTime, partyLabel, partyRouteLabel } from '../format.js'
import { Empty, useWide } from '../ui.jsx'

/**
 * 서버 대화 목록(/api/me/conversations)을 읽는다. 늦게 온 응답이나 실패가 최신 목록을 덮지 않고,
 * 새 채팅·상태 변경·화면 복귀 때 조용히 다시 읽는다. 읽지 못하면 null로 두어 state 기반 목록을 쓴다.
 */
function useConversations() {
  const [list, setList] = useState(null)
  const alive = useRef(true)
  const latest = useRef(0)
  const load = useCallback(async () => {
    if (typeof api.conversations !== 'function') return
    latest.current += 1
    const read = latest.current
    try {
      const next = await api.conversations()
      if (alive.current && read === latest.current && Array.isArray(next)) setList(next)
    } catch {
      // 실패는 조용히 넘기고 마지막으로 읽은 목록(또는 state 기반 목록)을 그대로 둔다.
    }
  }, [])
  useEffect(() => {
    alive.current = true
    load()
    let soon = null
    const later = () => { clearTimeout(soon); soon = setTimeout(load, 150) }
    const wake = () => document.visibilityState === 'visible' && later()
    window.addEventListener('taximate:chat', later)
    window.addEventListener('taximate:state', later)
    document.addEventListener('visibilitychange', wake)
    return () => {
      alive.current = false
      clearTimeout(soon)
      window.removeEventListener('taximate:chat', later)
      window.removeEventListener('taximate:state', later)
      document.removeEventListener('visibilitychange', wake)
    }
  }, [load])
  return list
}

/** state.parties·settledParty(바로 보이는 값)와 서버 대화 목록(미리보기·안 읽은 수)을 partyId로 합친다. */
export function mergeConversations(state, remote) {
  const byId = new Map()
  const local = [...(state?.parties || [])]
  if (state?.settledParty && !local.some((p) => p.id === state.settledParty.id)) local.push(state.settledParty)
  for (const party of local) {
    byId.set(party.id, {
      id: party.id, party, routeLabel: partyRouteLabel(party), departureAt: party.departureAt, status: party.status,
      memberCount: party.members?.length ?? null, unreadChat: party.unreadChat || 0, lastMessage: null, lastMessageAt: null,
    })
  }
  if (!Array.isArray(remote)) return [...byId.values()]
  const ordered = []
  for (const c of remote) {
    if (!c || c.partyId == null) continue
    const prev = byId.get(c.partyId)
    ordered.push({
      id: c.partyId,
      party: prev?.party || null,
      routeLabel: c.routeLabel || prev?.routeLabel || '일행 대화',
      departureAt: c.departureAt ?? prev?.departureAt ?? null,
      status: c.status || prev?.status,
      memberCount: c.memberCount ?? prev?.memberCount ?? null,
      unreadChat: c.unreadChat ?? prev?.unreadChat ?? 0,
      lastMessage: c.lastMessage || null,
      lastMessageAt: c.lastMessageAt || null,
      writable: c.writable,
    })
    byId.delete(c.partyId)
  }
  return [...ordered, ...byId.values()]
}

const badge = (count) => count > 0 && (
  <span className="unread-badge" aria-label={`안 읽은 메시지 ${count}개`}>{count > 99 ? '99+' : count}</span>
)

/**
 * 독립 메시지함 (/app/messages, /app/messages/:partyId).
 * 결제·탑승·정산 업무 카드와 분리된 순수 일행 대화 공간이며,
 * 상단 링크를 통해 관련 이용 상세(/parties/:id)로 양방향 이동할 수 있다.
 */
export default function MessagesPage({ state, activePartyId, onSelectParty, onOpenTrip, onHome, tabBar }) {
  const wide = useWide()
  const remote = useConversations()
  const conversations = mergeConversations(state, remote)

  const selected = activePartyId
    ? conversations.find((c) => c.id === activePartyId) || null
    : wide ? conversations[0] || null : null

  const showList = wide || !selected
  const showChat = wide || Boolean(selected)

  if (conversations.length === 0) {
    return (
      <div className="messages-empty-wrap">
        <div className="messages-empty-inner">
          <Empty title="참여 중인 일행 대화가 없어요" action="동승 찾기" onAction={onHome}>
            일행이 확정되면 이곳에서 만남 위치와 도착 소식을 주고받을 수 있어요.
          </Empty>
        </div>
        {!wide && tabBar}
      </div>
    )
  }

  const shortWhen = (c) => (c.lastMessageAt ? formatTime(c.lastMessageAt) : c.departureAt ? formatTime(c.departureAt) : '')
  const subline = (c) => c.lastMessage || (c.status ? partyLabel(c.status) : '대화를 시작해 보세요')

  return (
    <div className="messages-page">
      {showList && (
        <aside className="messages-sidebar" aria-label="대화 목록">
          <header className="messages-sidebar-head">
            <h1>메시지</h1>
          </header>
          <ul className="messages-list">
            {conversations.map((c) => {
              const active = selected?.id === c.id
              return (
                <li key={c.id}>
                  <button
                    type="button"
                    className={active ? 'messages-item on' : 'messages-item'}
                    aria-current={active ? 'true' : undefined}
                    onClick={() => onSelectParty(c.id)}
                  >
                    <span className="messages-item-main">
                      <span className="messages-item-top">
                        <b>{c.routeLabel}</b>
                        <small className="messages-item-time">{shortWhen(c)}</small>
                      </span>
                      <span className="messages-item-row">
                        <span className="messages-item-preview">{subline(c)}</span>
                        {badge(c.unreadChat)}
                      </span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
          {!wide && tabBar}
        </aside>
      )}

      {showChat && (
        <section className="messages-chat-pane" aria-label="일행 대화">
          {selected ? (
            <>
              <header className="messages-chat-head">
                <div className="messages-chat-title">
                  {!wide && (
                    <button type="button" className="icon-btn" aria-label="대화 목록으로" onClick={() => onSelectParty(null)}>
                      <ChevronLeft size={22} />
                    </button>
                  )}
                  <div>
                    <h2>{selected.routeLabel}</h2>
                    <small>
                      {selected.departureAt ? `${formatTime(selected.departureAt)} 출발 · ` : ''}
                      {selected.memberCount != null ? `${selected.memberCount}명 · ` : ''}{partyLabel(selected.status)}
                    </small>
                  </div>
                </div>
                <button type="button" className="messages-trip-link" onClick={() => onOpenTrip(selected.id)}>
                  <span>이용 상세</span>
                  <ChevronRight size={15} aria-hidden="true" />
                </button>
              </header>
              <div className="messages-chat-body">
                <PartyChat
                  key={selected.id}
                  partyId={selected.id}
                  now={state?.now}
                  status={selected.status}
                  active
                  full
                />
              </div>
            </>
          ) : (
            <div className="messages-placeholder">
              <MessageCircle size={24} aria-hidden="true" />
              <p>왼쪽에서 대화를 선택해 주세요.</p>
            </div>
          )}
        </section>
      )}
    </div>
  )
}
