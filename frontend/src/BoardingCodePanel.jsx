import React, { useEffect, useRef, useState } from 'react'
import { Copy } from 'lucide-react'
import { api } from './api.js'
import { Btn, toast } from './ui.jsx'
import { formatTime } from './format.js'

const formatCountdown = (ms) => {
  const total = Math.max(0, Math.ceil(ms / 1000))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** Arrival is a location signal; only the payer's actual boarding opens this window. */
export default function BoardingCodePanel({ party, now, onUpdated, active = true, allowCall = false, demo = false }) {
  const [state, setState] = useState(null)
  const [demoCode, setDemoCode] = useState(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const panel = useRef(null)
  const anchor = useRef({ server: now, wall: Date.now() })
  const [wall, setWall] = useState(Date.now)
  useEffect(() => {
    anchor.current = { server: now, wall: Date.now() }
    setWall(Date.now())
  }, [now])
  useEffect(() => {
    let live = true
    let loading = false
    setState(null); setCode('')
    const load = async () => {
      if (loading) return
      loading = true
      try { const value = await api.boardingState(party.id); if (live) setState(value) }
      catch (e) { if (live) setError(e.message) }
      finally { loading = false }
    }
    if (active) load()
    const poll = setInterval(() => {
      if (active && document.visibilityState !== 'hidden' && !panel.current?.closest('[hidden]')) load()
    }, 5000)
    return () => { live = false; clearInterval(poll) }
  }, [party.id, party.payerId, party.status, party.boardedAt, party.calledAt, party.groupVersion, active])
  useEffect(() => {
    const timer = setInterval(() => setWall(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  // 시연: 결제자가 시연용 학생이면 그 폰을 볼 수 없으니, 입력 창이 열린 동안 결제자 화면의 번호를 대신 알려 준다.
  const waitingForCode = demo && !party.payer && state?.status === 'OPEN' && !state?.confirmed
  useEffect(() => {
    let live = true
    setDemoCode(null)
    if (waitingForCode) api.demoPayerCode(party.id).then((r) => { if (live) setDemoCode(r?.code || null) }).catch(() => {})
    return () => { live = false }
  }, [waitingForCode, party.id, state?.generation])
  const act = async (work) => {
    setBusy(true); setError('')
    try { await work(); setCode(''); await onUpdated?.() } catch (e) { setError(e.message) }
    finally {
      try { setState(await api.boardingState(party.id)) } catch (e) { setError(e.message) }
      setBusy(false)
    }
  }
  const copyCode = async () => {
    if (!state?.code) return
    try {
      await navigator.clipboard.writeText(state.code)
      toast('탑승 번호를 복사했어요')
    } catch {
      toast('복사하지 못했습니다. 화면 번호를 직접 알려 주세요.', 'bad')
    }
  }
  if (!state) return error ? <p role="alert">{error}</p> : null
  const currentMs = Number.isFinite(Date.parse(anchor.current.server))
    ? Date.parse(anchor.current.server) + Math.max(0, wall - anchor.current.wall)
    : wall
  const deadlineMs = state.deadlineAt ? Date.parse(state.deadlineAt) : null
  const leftMs = deadlineMs != null ? deadlineMs - currentMs : null
  const expired = leftMs != null && leftMs <= 0
  const windowOpen = state.status === 'OPEN' && !expired
  const urgent = windowOpen && leftMs != null && leftMs <= 60000
  const canCall = (allowCall || party.status === 'RIDE_UNCONFIRMED') && party.payer && !party.calledAt && party.canCall
  const showCode = party.payer && windowOpen && state.code
  const typing = !party.payer && !state.confirmed && windowOpen
  const ended = (state.status === 'EXPIRED' || expired) && !state.confirmed
  const actionable = canCall || state.canOpen || showCode || typing || state.canStart || ended || (!party.payer && state.confirmed)
  // 아직 할 일이 없으면 한 줄로만 알려 준다. 타기 전부터 큰 상자를 띄우지 않는다.
  if (!actionable) return <section ref={panel} className="board-hint" aria-label="탑승 번호 확인">
    <p>택시에 타면 결제자 화면의 탑승 번호로 탑승을 확인해요.</p>
    {error && <p role="alert">{error}</p>}
  </section>
  return <section ref={panel} className="next-action boarding" aria-label="탑승 번호 확인">
    <div className="boarding-head">
      <h2>{showCode ? '탑승 번호' : typing ? '탑승 번호를 입력해 주세요' : '택시에 타면 탑승을 확인해 주세요'}</h2>
      {state.deadlineAt && windowOpen && <span className={urgent ? 'code-timer urgent' : 'code-timer'}>{formatCountdown(leftMs)}</span>}
    </div>
    {showCode && <>
      <div className="ticket-box">
        <strong className="ticket" aria-label="탑승 번호">{state.code}</strong>
        <button type="button" className="ticket-copy" aria-label="탑승 번호 복사" onClick={copyCode}><Copy size={15} aria-hidden="true" />복사</button>
      </div>
      <p>탑승한 일행에게 이 번호를 보여 주세요.</p>
    </>}
    {state.deadlineAt && windowOpen && <p className="boarding-deadline">입력 마감 {formatTime(state.deadlineAt)}</p>}
    {!showCode && !typing && !ended && <p>결제자가 탄 뒤 5분 동안 번호를 입력해요.</p>}
    {canCall && <Btn disabled={busy} onClick={() => act(() => api.callParty(party.id))}>택시 불렀어요</Btn>}
    {state.canOpen && <Btn disabled={busy} onClick={() => act(() => api.boarded(party.id))}>택시 탔어요 · 탑승 번호 열기</Btn>}
    {!party.payer && state.confirmed && <p className="ok">탑승 확인 완료</p>}
    {typing && <form className="code-form" onSubmit={(e) => { e.preventDefault(); act(() => api.confirmBoardingCode(party.id, code, state.generation)) }}>
      <label className="field code"><span className="sr-only">결제자 화면의 탑승 번호</span><input aria-label="6자리 탑승 번호" inputMode="numeric" autoComplete="off" maxLength={6} placeholder="000000" value={code} onChange={(e) => setCode(e.target.value.replace(/[^0-9]/g, ''))} /></label>
      {demoCode && <p className="note demo-code">시연: 결제자 화면의 번호는 <strong>{demoCode}</strong>예요.</p>}
      <div className="code-meta">
        <span>남은 입력 횟수 {state.remainingAttempts}회</span>
        <span className="code-progress">{code.length}/6</span>
      </div>
      <Btn disabled={busy || code.length !== 6 || state.remainingAttempts === 0} type="submit">탑승 확인</Btn>
    </form>}
    {ended && <p>번호 입력 시간이 끝났어요. 입력하지 못했다는 이유만으로 노쇼가 되지 않아요.</p>}
    {state.canStart && <><p className="boarding-start-hint">확인 안 된 일행이 있어도 출발할 수 있어요.</p><Btn disabled={busy} onClick={() => act(() => api.startRide(party.id))}>운행 시작</Btn></>}
    {error && <p role="alert">{error}</p>}
  </section>
}
