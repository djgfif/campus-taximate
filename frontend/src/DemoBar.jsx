import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
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
  if (!party) return ['길 올리고 일행 찾기', '홈에서 길을 올린 뒤 "일행 만들기"를 누르면 같은 방향 학생과 묶여요. 길을 안 올렸으면 시연 경로로 올려요.']
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

/** 발표자 창과 관객 창이 같은 브라우저에서 장면을 주고받는 통로. */
const CHANNEL = 'taximate-demo'
const openChannel = () => (typeof BroadcastChannel === 'function' ? new BroadcastChannel(CHANNEL) : null)

/** 시연 동작 이후 지금 일행과 장면을 읽는다. 일행이 없으면 첫 장면이다. */
async function readScene() {
  const status = await api.demoStatus().catch(() => null)
  const id = status?.partyId
  const party = id ? await api.party(id).catch(() => null) : null
  return { id: id || null, scene: sceneOf(party), title: party ? guide(party)[0] : SCENES[0].title }
}

/**
 * 시연 창 본문. 장면을 바꾸는 동작(일행 만들기, 다음 장면, 다른 장면)은 끝나면 onScene으로 새 장면을 알린다.
 * 시간 넘기기 같은 보조 동작은 창을 연 채로 둔다.
 */
export function DemoPanel({ now, run, parties = [], accounts = null, onScene }) {
  const [role, setRole] = useState('PAYER')
  const [progress, setProgress] = useState(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const acting = useRef(false)
  useEffect(() => {
    let closed = false
    api.demoStatus().then((p) => { if (!closed) { setProgress(p); setError('') } })
      .catch((e) => !closed && setError(e.message))
    return () => { closed = true }
  }, [now, reload])
  const act = (action, changesScene = true) => async () => {
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
      if (changesScene && onScene) onScene(await readScene())
    } finally { acting.current = false; setBusy(false) }
  }
  // 서버가 알려 준 일행을 먼저, 아직 모르면 내 첫 일행을 쓴다.
  const party = progress ? parties.find((p) => p.id === progress.partyId) || null : parties[0] || null
  const scene = sceneOf(party)
  const [title, hint] = guide(party)
  return <>
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
      <Btn className="line" disabled={busy} onClick={act(api.demoBoardParty, false)}>다른 학생이 모으는 일행 만들기</Btn>
    </details>
    <details className="demo-more">
      <summary>시간 넘기기</summary>
      <div className="demo-grid">
        <Btn className="line" disabled={busy} onClick={act(api.demoAct, false)}>다른 일행이 움직이기</Btn>
        <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(5), false)}>5분 뒤</Btn>
        <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(30), false)}>30분 뒤</Btn>
        <Btn className="line" disabled={busy} onClick={act(() => api.demoAdvance(1440), false)}>하루 뒤</Btn>
      </div>
    </details>
    {accounts && <details className="demo-more">
      <summary>계정 바꾸기</summary>
      {accounts}
    </details>}
  </>
}

/** 장면이 바뀌면 화면 위에 잠깐 뜨는 자막. 관객이 지금 무엇을 보는지 알게 한다. */
function SceneCaption({ caption }) {
  if (!caption) return null
  return <p className="demo-caption" role="status"><b>장면 {caption.scene + 1} / {SCENES.length}</b>{caption.title}</p>
}

const typing = (target) => target instanceof HTMLElement
  && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))

/**
 * 관객 화면의 시연 도구. 휴대폰은 위쪽 작은 '시연' 단추로 창을 연다.
 * 넓은 화면은 단추를 숨기고 키보드로만 다룬다: D는 시연 창, →는 다음 장면.
 * 장면이 바뀌면 창을 닫고 자막을 띄운 뒤 그 일행방으로 간다. 발표자 창(/presenter)에서 바꿔도 똑같이 따라간다.
 */
export default function DemoBar({ now, run, refresh, parties = [], accounts = null, wide = false }) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [caption, setCaption] = useState(null)
  const timer = useRef(null)
  const stepping = useRef(false)
  const showScene = useCallback((info) => {
    setOpen(false)
    setCaption(info)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCaption(null), 3200)
    if (info?.id) navigate('/parties/' + info.id)
  }, [navigate])
  useEffect(() => () => clearTimeout(timer.current), [])
  // 발표자 창에서 장면을 바꾸면 이 창도 새로 읽고 따라간다.
  useEffect(() => {
    const channel = openChannel()
    if (!channel) return undefined
    channel.onmessage = async (event) => {
      if (event.data?.type !== 'scene') return
      await refresh?.()
      showScene(event.data.info)
    }
    return () => channel.close()
  }, [refresh, showScene])
  useEffect(() => {
    const key = async (event) => {
      if (event.altKey || event.ctrlKey || event.metaKey || typing(event.target)) return
      if (event.key === 'd' || event.key === 'D') { setOpen((v) => !v); return }
      if (event.key !== 'ArrowRight' || open || document.querySelector('[role="dialog"]') || stepping.current) return
      stepping.current = true
      try {
        const hasParty = parties.length > 0
        await run(() => (hasParty ? api.demoNext('PAYER') : api.demoMatch('PAYER')))
        showScene(await readScene())
      } finally { stepping.current = false }
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [open, parties.length, run, showScene])
  return <>
    {!wide && <button type="button" className="demo-fab" onClick={() => setOpen(true)}>시연</button>}
    <SceneCaption caption={caption} />
    {open && <Sheet title="시연" className="pop demo" onClose={() => setOpen(false)}>
      <DemoPanel now={now} run={run} parties={parties} accounts={accounts} onScene={showScene} />
      {wide && <p className="note">키보드: D 시연 창, → 다음 장면. 발표자 창은 주소 끝에 /presenter를 붙여 따로 여세요.</p>}
    </Sheet>}
  </>
}

/** 발표자 창(/presenter). 관객 창과 같은 계정으로 따로 띄워 두고, 여기서 누르면 관객 창이 따라 바뀐다. */
export function DemoPresenter({ now, run, parties, accounts }) {
  const channel = useRef(null)
  const [last, setLast] = useState(null)
  useEffect(() => {
    channel.current = openChannel()
    return () => channel.current?.close()
  }, [])
  return <div className="presenter">
    <header className="presenter-head">
      <h1>발표자 창</h1>
      <p className="note">관객에게 보이는 창은 따로 띄워 두세요. 여기서 장면을 바꾸면 그 창이 자막과 함께 따라 바뀌어요.</p>
    </header>
    {last && <p className="presenter-last">방금 보낸 장면: {last.scene + 1} {last.title}</p>}
    <DemoPanel now={now} run={run} parties={parties} accounts={accounts} onScene={(info) => {
      setLast(info)
      channel.current?.postMessage({ type: 'scene', info })
    }} />
  </div>
}
