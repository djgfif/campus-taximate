import React, { useEffect, useRef, useState } from 'react'
import { api } from './api.js'
import { formatDateTime } from './format.js'
import { Btn, Chips, Say, Sheet } from './ui.jsx'

const scenarios = [
  ['INSTANT', '즉시 매칭'], ['NO_SHOW', '노쇼 발생'], ['JOIN', '나중 일행 합류'],
  ['FARE_OBJECTION', '요금 이의'], ['PAYER_NO_SHOW', '결제자 노쇼·승계 요청'],
  ['MY_SUCCESSION', '내가 결제자 승계'], ['RIDE_UNCONFIRMED', '운행 미확인 (T+5분)'],
]
const caseLabels = { PENDING: '탑승 미확인 · 판정 대기', REVIEW: '관리자 검토', CONFIRMED: '노쇼 확정', EXONERATED: '노쇼 아님' }

export default function DemoBar({ now, run }) {
  const [open, setOpen] = useState(false)
  const [role, setRole] = useState('PAYER')
  const [progress, setProgress] = useState(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const acting = useRef(false)
  useEffect(() => {
    if (!open) return undefined
    let closed = false
    api.demoStatus().then((p) => { if (!closed) { setProgress(p); setError('') } })
      .catch((e) => !closed && setError(e.message))
    return () => { closed = true }
  }, [open, now, reload])
  const act = (action) => async () => {
    if (acting.current) return
    acting.current = true; setBusy(true); setError(''); setMessage('')
    try {
      await run(async () => {
        const result = await action()
        if (result?.stage) setProgress(result)
        if (result?.message) setMessage(result.message)
        return result
      })
      setReload((n) => n + 1)
    } finally { acting.current = false; setBusy(false) }
  }
  return <>
    <button type="button" className="demo-fab" onClick={() => setOpen(true)}>시연</button>
    {open && <Sheet title="시연" className="pop demo" onClose={() => setOpen(false)}>
      <section aria-label="시연 진행 상태" aria-live="polite">
        <p>시연 시각 {formatDateTime(progress?.now || now)}</p>
        {progress && <><p>현재 단계: {progress.stage}</p><p>다음 단계: {progress.next}</p>{progress.role && <b>{progress.role}</b>}</>}
        {progress?.investigations?.map((c) => <p key={c.memberId}>회원 {c.memberId}: {caseLabels[c.status] || '판정 확인 중'} · {formatDateTime(c.deadlineAt)}</p>)}
      </section>
      {message && <Say>{message}</Say>}{error && <p role="alert">{error}</p>}
      <Chips label="내 시연 역할" value={role} onChange={setRole} options={[
        { value: 'PAYER', label: '내가 결제자' }, { value: 'PASSENGER', label: '내가 결제자 아님(선결제 체험)' },
      ]} />
      <Btn disabled={busy} onClick={act(() => api.demoMatch(role))}>일행 만들기</Btn>
      <Btn disabled={busy} onClick={act(api.demoNext)}>다음 단계로</Btn>
      <Btn className="line" disabled={busy} onClick={act(api.demoBoardParty)}>다른 학생 일행 만들기 (모집 중 체험)</Btn>
      <section aria-label="시나리오">
        <h2 className="block-title">시나리오</h2>
        <p className="note">요청이 없으면 시연용 경로를 만들어요. 자동 결제 시나리오는 모의 결제 모드에서 진행해요.</p>
        <div className="demo-grid">{scenarios.map(([id, label]) => <Btn className="line" disabled={busy} key={id} onClick={act(() => api.demoScenario(id, role))}>{label}</Btn>)}</div>
      </section>
      <details><summary>직접 조작</summary><div className="demo-grid">
        <Btn className="line" disabled={busy} onClick={act(api.demoAct)}>샘플 일행 행동</Btn>
        <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(5))}>+5분</Btn>
        <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(30))}>+30분</Btn>
        <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(1440))}>+24시간</Btn>
      </div></details>
    </Sheet>}
  </>
}
