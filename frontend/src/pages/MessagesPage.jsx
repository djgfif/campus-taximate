import React from 'react'
import { ChevronLeft, ExternalLink, MessageCircle } from 'lucide-react'
import { PartyChat } from '../PartyChat.jsx'
import { formatTime, partyLabel, partyRouteLabel } from '../format.js'
import { Btn, Empty, useWide } from '../ui.jsx'

/**
 * 독립 메시지함 (/app/messages, /app/messages/:partyId).
 * 결제·탑승·정산 업무 카드와 분리된 순수 일행 대화 공간이며,
 * 상단 링크를 통해 관련 이용 상세(/parties/:id)로 양방향 이동할 수 있다.
 */
export default function MessagesPage({ state, activePartyId, onSelectParty, onOpenTrip, onHome, tabBar }) {
  const wide = useWide()
  const activeParties = state?.parties || []
  const conversations = [...activeParties]
  if (state?.settledParty && !conversations.some((p) => p.id === state.settledParty.id)) {
    conversations.push(state.settledParty)
  }

  const selected = activePartyId
    ? conversations.find((p) => p.id === activePartyId) || null
    : wide ? conversations[0] || null : null

  const showList = wide || !selected
  const showChat = wide || Boolean(selected)

  if (conversations.length === 0) {
    return (
      <div className="messages-empty-wrap">
        <div className="home-card messages-empty-card">
          <Empty title="참여 중인 일행 대화가 없어요" action="동승 찾기" onAction={onHome}>
            일행이 확정되면 이곳에서 만남 위치와 도착 소식을 주고받을 수 있어요.
          </Empty>
        </div>
        {!wide && tabBar}
      </div>
    )
  }

  return (
    <div className="messages-page">
      {showList && (
        <aside className="messages-sidebar" aria-label="대화 목록">
          <header className="messages-sidebar-head">
            <h1>메시지</h1>
            <span className="home-count">{conversations.length}</span>
          </header>
          <ul className="messages-list">
            {conversations.map((party) => {
              const active = selected?.id === party.id
              return (
                <li key={party.id}>
                  <button
                    type="button"
                    className={active ? 'messages-item on' : 'messages-item'}
                    aria-current={active ? 'true' : undefined}
                    onClick={() => onSelectParty(party.id)}
                  >
                    <span className="messages-item-main">
                      <b>{partyRouteLabel(party)}</b>
                      <small>
                        {party.departureAt ? `${formatTime(party.departureAt)} 출발 · ` : ''}
                        {partyLabel(party.status)}
                      </small>
                    </span>
                    {party.unreadChat > 0 && (
                      <span className="unread-badge" aria-label={`안 읽은 메시지 ${party.unreadChat}개`}>
                        {party.unreadChat > 99 ? '99+' : party.unreadChat}
                      </span>
                    )}
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
                    <h2>{partyRouteLabel(selected)}</h2>
                    <small>
                      {selected.departureAt ? `${formatTime(selected.departureAt)} 출발 · ` : ''}
                      {(selected.members || []).length}명 · {partyLabel(selected.status)}
                    </small>
                  </div>
                </div>
                <Btn className="small line" onClick={() => onOpenTrip(selected.id)}>
                  이용 상세
                  <ExternalLink size={14} aria-hidden="true" />
                </Btn>
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
              <MessageCircle size={28} aria-hidden="true" />
              <p>왼쪽에서 대화를 선택해 주세요.</p>
            </div>
          )}
        </section>
      )}
    </div>
  )
}
