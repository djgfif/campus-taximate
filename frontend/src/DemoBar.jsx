import React, { useEffect, useRef, useState } from 'react'
import { Check } from 'lucide-react'
import { api } from './api.js'
import { formatDateTime } from './format.js'
import { Btn, Chips, Say, Sheet } from './ui.jsx'

/**
 * 시연은 일곱 장면으로 보여 준다. 서버 상태 이름 대신 사람이 하는 일로 부르고,
 * 장면마다 "지금 무엇을 눌러 보면 되는지" 한 줄을 붙인다.
 */
export const SCENES = [
  { title: '길 올리고 일행 찾기', statuses: [] },
  { title: '일행 확정, 선결제', statuses: ['PREPAYING'] },
  { title: '승차 지점에 모이기', statuses: ['READY', 'PAYER_PENDING'] },
  { title: '택시 탔는지 확인', statuses: ['RIDE_UNCONFIRMED'] },
  { title: '실제 요금 입력', statuses: ['AWAITING_FARE'] },
  { title: '요금 확인 기간', statuses: ['OBJECTION_WINDOW', 'REVIEW_PENDING'] },
  { title: '비율대로 정산 끝', statuses: ['SETTLED', 'COMPLETED'] },
]

/** 일행 상태로 장면 번호(0부터)를 고른다. 일행이 없으면 첫 장면이다. */
export const sceneOf = (party) => {
  if (!party) return 0
  const index = SCENES.findIndex((scene) => scene.statuses.includes(party.status))
  return index < 0 ? SCENES.length - 1 : index
}

/** 지금 장면의 제목과 "무엇을 눌러 보면 되는지". */
function guide(party) {
  if (!party) return ['길 올리고 일행 찾기', '역할을 고르고 "일행 만들기"를 누르면 시연 경로로 요청을 올리고 일행을 만들어요.']
  const payer = party.payer
  switch (party.status) {
    case 'PREPAYING': return ['일행이 정해졌어요', payer ? '나는 결제자라 선결제가 없어요. "다음 장면 보기"로 다른 사람들의 결제를 넘겨 보세요.' : '일행방 아래 카드에서 선결제를 해 보세요.']
    case 'READY': return ['승차 지점에 모여요', payer ? '모두 도착하면 "택시 불렀어요"를 눌러 보세요.' : '일행방 아래 "도착했어요"를 눌러 보세요.']
    case 'PAYER_PENDING': return ['결제자가 오지 않았어요', '일행방 아래 카드에서 "제가 결제할게요"를 눌러 보세요.']
    case 'RIDE_UNCONFIRMED': return ['택시 탔는지 물어봐요', '일행방 아래 카드에서 "탔어요"나 "못 탔어요"를 눌러 보세요.']
    case 'AWAITING_FARE': return ['실제 요금을 입력해요', payer ? '실제 택시비와 영수증을 입력해 보세요.' : '결제자가 요금을 입력하면 내 몫이 정해져요. "다음 장면 보기"로 넘겨 보세요.']
    case 'OBJECTION_WINDOW':
    case 'REVIEW_PENDING': return ['요금을 확인하는 시간이에요', '요금이 이상하면 이의를 낼 수 있어요. "다음 장면 보기"를 누르면 24시간이 지나요.']
    default: return ['정산이 끝났어요', '혼자 탈 때보다 얼마 덜 냈는지 일행방에서 확인해 보세요.']
  }
}

const OTHER_SCENES = [
  ['PAYER_NO_SHOW', '결제자가 안 왔을 때'], ['MY_SUCCESSION', '내가 결제를 이어받을 때'], ['NO_SHOW', '한 명이 안 왔을 때'],
  ['JOIN', '나중에 한 명이 더 탈 때'], ['FARE_OBJECTION', '요금에 이의를 낼 때'], ['INSTANT', '바로 출발 일행'],
  ['RIDE_UNCONFIRMED', '탔는지 답이 없을 때'],
]
const caseLabels = { PENDING: '안 왔는지 확인하는 중', REVIEW: '관리자가 보는 중', CONFIRMED: '노쇼로 확정', EXONERATED: '노쇼 아님' }

/**
 * 시연 단추와 창. inline이면 넓은 화면 위쪽 메뉴 안에 글자 단추로 놓인다.
 * parties는 내 진행 중 일행, accounts는 계정 바꾸기 목록이다.
 */
export default function DemoBar({ now, run, parties = [], accounts = null, inline = false }) {
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
  // 서버가 알려 준 일행을 먼저, 아직 모르면 내 첫 일행을 쓴다.
  const party = progress ? parties.find((p) => p.id === progress.partyId) || null : parties[0] || null
  const scene = sceneOf(party)
  const [title, hint] = guide(party)
  return <>
    <button type="button" className={inline ? 'demo-nav' : 'demo-fab'} onClick={() => setOpen(true)}>시연</button>
    {open && <Sheet title="시연" className="pop demo" onClose={() => setOpen(false)}>
      <section className="demo-now" aria-label="지금 장면" aria-live="polite">
        <p className="note">장면 {scene + 1} / {SCENES.length}, 시연 시각 {formatDateTime(progress?.now || now)}</p>
        <h3>{title}</h3>
        <p>{hint}</p>
        {progress?.investigations?.map((c) => <p className="note" key={c.memberId}>{c.nickname || '일행'}님: {caseLabels[c.status] || '확인 중'}, {formatDateTime(c.deadlineAt)}까지</p>)}
      </section>
      {message && <Say>{message}</Say>}{error && <p role="alert">{error}</p>}
      {party ? <Btn disabled={busy} onClick={act(() => api.demoNext(role))}>다음 장면 보기</Btn> : <>
        <Chips label="내 역할" value={role} onChange={setRole} options={[
          { value: 'PAYER', label: '내가 결제자' }, { value: 'PASSENGER', label: '선결제하는 일행' },
        ]} />
        <Btn disabled={busy} onClick={act(() => api.demoMatch(role))}>일행 만들기</Btn>
      </>}
      <ol className="demo-scenes" aria-label="장면 순서">
        {SCENES.map((s, i) => <li key={s.title} className={i < scene ? 'done' : i === scene ? 'now' : ''} aria-current={i === scene ? 'step' : undefined}>
          <span>{i + 1} {s.title}</span>
          {i < scene ? <Check size={16} aria-label="지남" /> : i === scene ? <small>지금</small> : null}
        </li>)}
      </ol>
      <details className="demo-more">
        <summary>다른 장면 체험</summary>
        <p className="note">요청이 없으면 시연 경로를 만들어요. 진행 중인 일행이 있으면 그 일행에서 실행해요.</p>
        <div className="demo-grid">{OTHER_SCENES.map(([id, label]) => <Btn className="line" disabled={busy} key={id} onClick={act(() => api.demoScenario(id, role))}>{label}</Btn>)}</div>
        <Btn className="line" disabled={busy} onClick={act(api.demoBoardParty)}>다른 학생이 모으는 일행 만들기</Btn>
      </details>
      <details className="demo-more">
        <summary>시간 넘기기</summary>
        <div className="demo-grid">
          <Btn className="line" disabled={busy} onClick={act(api.demoAct)}>다른 일행이 움직이기</Btn>
          <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(5))}>5분 뒤</Btn>
          <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(30))}>30분 뒤</Btn>
          <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(1440))}>하루 뒤</Btn>
        </div>
      </details>
      {accounts && <details className="demo-more">
        <summary>계정 바꾸기</summary>
        {accounts}
      </details>}
    </Sheet>}
  </>
}
