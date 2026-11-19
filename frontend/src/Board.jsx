import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import PaymentCard from './PaymentCard.jsx'
import { PlaceField } from './RequestFlow.jsx'
import { formatTime } from './format.js'
import { Avatar, Btn, Confirm, Say, Screen, Sheet, TopBar, toast } from './ui.jsx'

const hourLabel = (iso) => `${formatTime(iso)}대`

/**
 * 홈의 "지금 모집 중". 같은 방향으로 이미 모인 일행과, 시간대별로 기다리는 사람 수를 보여 준다.
 * 일행 카드를 누르면 내릴 곳만 고르고 바로 합류를 신청한다(나머지는 기존 일행 조건을 따른다).
 */
export function BoardSection({ now, config, onJoined, disabled }) {
  const [board, setBoard] = useState(null)
  const [error, setError] = useState('')
  const [joining, setJoining] = useState(null)
  useEffect(() => {
    let closed = false
    api.board().then((next) => { if (!closed) { setBoard(next); setError('') } }).catch((e) => !closed && setError(e.message))
    return () => { closed = true }
  }, [now])
  if (error) return null
  const parties = board?.parties || []
  const waiting = board?.waiting || []
  return (
    <section className="board" aria-label="지금 모집 중">
      <div className="board-head">
        <h2 className="block-title">지금 모집 중</h2>
        {board && <span className="live" aria-hidden="true">실시간</span>}
      </div>
      {!board && <p className="note" role="status">불러오는 중…</p>}
      {board && parties.length === 0 && (
        <p className="board-empty">아직 모인 일행이 없어요. 먼저 가는 길을 올리면 같은 방향 학생과 이어 드려요.</p>
      )}
      <ul className="board-list">
        {parties.map((card) => (
          <li key={card.id}>
            <button type="button" className="board-card" disabled={disabled} onClick={() => setJoining(card)}
              aria-label={`${formatTime(card.departureAt)} ${card.anchorName}에서 출발, ${card.riders}명, 자리 ${card.seats}개`}>
              <span className="when"><b>{formatTime(card.departureAt)}</b><small>출발</small></span>
              <span className="what">
                <b>{card.anchorName} · {card.meetingPoint}</b>
                <span className="faces">
                  {card.people.map((p, i) => <Avatar key={i} name={p.nickname} size={24} />)}
                  <small>{card.people.map((p) => p.profile || p.nickname).join(', ')}</small>
                </span>
              </span>
              <span className="seats">{card.seats}자리</span>
            </button>
          </li>
        ))}
      </ul>
      {waiting.length > 0 && (
        <p className="board-waiting">
          {waiting.map((w) => `${hourLabel(w.hour)} ${w.direction === 'TO_SCHOOL' ? '등교' : '하교'} ${w.count}명`).join(' · ')} 기다리는 중
        </p>
      )}
      {joining && <JoinSheet card={joining} config={config} onClose={() => setJoining(null)} onJoined={onJoined} />}
    </section>
  )
}

function JoinSheet({ card, config, onClose, onJoined }) {
  const [place, setPlace] = useState({ name: '', point: null })
  const [memo, setMemo] = useState('')
  const [error, setError] = useState('')
  const center = { lat: card.anchorLat, lon: card.anchorLon }
  const submit = async () => {
    setError('')
    try {
      await api.boardJoin(card.id, { pointName: place.name, pointLat: place.point.lat, pointLon: place.point.lon, memo })
      toast('합류를 신청했어요. 선결제하면 일행에 들어가요')
      onClose()
      await onJoined?.()
    } catch (e) { setError(e.message) }
  }
  return (
    <Sheet title={`${formatTime(card.departureAt)} 일행에 합류`} onClose={onClose}>
      <div className="join-who">
        {card.people.map((p, i) => <span key={i} className="join-person"><Avatar name={p.nickname} size={36} /><b>{p.nickname}</b>{p.profile && <small>{p.profile}</small>}</span>)}
      </div>
      <p className="sub">{card.anchorName} · {card.meetingPoint}에서 {formatTime(card.departureAt)}에 출발해요.</p>
      <PlaceField label="내릴 곳" value={place} center={center.lat ? center : null} onChange={setPlace} samples={config?.demo ? config.sampleDropoffs || [] : []} />
      <label className="field">일행에게 한마디 (선택)<input maxLength={100} value={memo} placeholder="예: 검은 백팩 멨어요" onChange={(e) => setMemo(e.target.value)} /></label>
      {error && <p role="alert">{error}</p>}
      <p className="note">기존 일행의 몫이 오르지 않고 경로가 맞을 때만 합류돼요. {formatTime(card.joinUntil)}까지 신청할 수 있어요.</p>
      <Btn disabled={!place.point || !place.name.trim()} onClick={submit}>합류 신청</Btn>
    </Sheet>
  )
}

/** 합류를 신청했고 선결제를 기다리는 동안의 화면. 결제가 승인되면 일행 화면으로 바뀐다. */
export function Joining({ latest, config, now, myId, run, onUpdated, onMenu }) {
  const [join, setJoin] = useState(null)
  const [asking, setAsking] = useState(false)
  useEffect(() => {
    let closed = false
    api.myJoins().then((list) => !closed && setJoin((list || []).find((j) => j.requestId === latest.id) || null)).catch(() => {})
    return () => { closed = true }
  }, [latest.id, now])
  return (
    <Screen foot={<Btn className="line" onClick={() => setAsking(true)}>합류 취소</Btn>}>
      <TopBar onMenu={onMenu} />
      <div className="pad">
        <h1 className="head">선결제하면<br />일행에 들어가요</h1>
        {join ? <>
          <p className="sub">{formatTime(join.departureAt)}에 출발하는 일행이에요. 결제하면 바로 같이 타는 사람이 돼요.</p>
          <PaymentCard compact partyId={join.partyId} myId={myId} now={now} config={config} onUpdated={onUpdated} />
        </> : <p role="status">합류 정보를 확인하고 있어요.</p>}
        <Say tone="calm">기한 안에 결제하지 않으면 합류가 취소되고, 결제한 금액은 전액 돌려드려요.</Say>
      </div>
      {asking && <Confirm title="합류를 취소할까요?" action="합류 취소" onClose={() => setAsking(false)}
        onConfirm={() => run(() => api.cancelRequest(latest.id), '합류를 취소했어요')}>결제했다면 전액 돌려드려요.</Confirm>}
    </Screen>
  )
}
