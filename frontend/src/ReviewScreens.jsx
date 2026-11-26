import React, { useCallback, useEffect, useRef, useState } from 'react'
import { ExternalLink } from 'lucide-react'
import { api } from './api.js'
import { formatDateTime, isPaymentParty, won } from './format.js'
import { BackBar, Btn, Fold, Rows, Say, Screen, toast } from './ui.jsx'
import AdminLayout from './layouts/AdminLayout.jsx'
import './reviews.css'

const CASE_LABEL = { PENDING: '소명 기간', REVIEW: '관리자 검토 중', CONFIRMED: '노쇼 확정', EXONERATED: '노쇼 아님' }
const CASE_TONE = { PENDING: 'warn', REVIEW: 'warn', CONFIRMED: 'bad', EXONERATED: 'ok' }
const AUDIT_ACTION = {
  NO_SHOW_DECISION: '노쇼 판정',
  FARE_DECISION: '요금 이의 판정',
  RIDE_DECISION: '운행 여부 판정',
  OVERDUE_HANDLED: '미정산 확인',
  RESTRICT: '회원 이용 제한',
  RELEASE: '이용 제한 해제',
  HUB_SAVE: '승차 지점 저장',
}
// 서버 AdminService.audit 호출이 남기는 값과 같아야 한다. 판단 불가는 서버가 NOT_NO_SHOW로 저장한다.
const AUDIT_TARGET = {
  BOARDING_CASE: '탑승 조사',
  PARTY: '일행',
  PAYMENT_ORDER: '결제 주문',
  RESTRICTION: '이용 제한',
  HUB: '승차 지점',
}
const AUDIT_DECISION = {
  NO_SHOW: '노쇼 확정',
  NOT_NO_SHOW: '노쇼 아님',
  OPERATED: '운행 확인',
  OPERATED_ADMIN_RECOVERY: '운행 확인 (관리자 복구)',
  NOT_OPERATED: '미운행 확인',
  ACCEPTED: '이의 인정',
  REJECTED: '이의 기각',
  HANDLED: '확인 완료',
  GRANTED: '제한 적용',
  RELEASED: '제한 해제',
  CREATED: '새로 등록',
  UPDATED: '수정',
}
const REPORT_REASON = { RUDE: '불쾌한 언행', DANGEROUS: '위험 행동', OTHER: '기타' }
const freshKey = () => globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`

function useRemote(load) {
  const [state, setState] = useState({ loading: true })
  const [revision, setRevision] = useState(0)
  const reload = useCallback(() => setRevision((value) => value + 1), [])
  useEffect(() => {
    let closed = false
    setState((old) => ({ ...old, loading: true, error: '' }))
    Promise.resolve().then(() => !closed && load()).then((data) => !closed && setState({ data, loading: false }))
      .catch((e) => !closed && setState((old) => ({ ...old, error: e.message, loading: false })))
    return () => { closed = true }
  }, [load, revision])
  return { ...state, reload }
}

function RemoteState({ remote }) {
  if (remote.error) return <Say tone="stop-tone" action={<Btn className="small line" onClick={remote.reload}>다시 불러오기</Btn>}>
    <p role="alert">{remote.error}</p>
    {remote.data && <p>아래는 마지막으로 확인한 내용이에요. 다시 불러와 최신 상태를 확인해 주세요.</p>}
  </Say>
  if (remote.loading) return <p className="note" role="status">불러오는 중이에요.</p>
  return null
}

function useClock(now) {
  const [wall, setWall] = useState(Date.now)
  const anchor = useRef({ server: now, wall: Date.now() })
  useEffect(() => { anchor.current = { server: now, wall: Date.now() }; setWall(Date.now()) }, [now])
  useEffect(() => { const timer = setInterval(() => setWall(Date.now()), 1000); return () => clearInterval(timer) }, [])
  return Number.isFinite(Date.parse(now)) ? Date.parse(anchor.current.server) + Math.max(0, wall - anchor.current.wall) : wall
}

function CaseCard({ item, currentTime, onSaved }) {
  const { investigation: c, explanation, votes = [] } = item
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const open = c.status === 'PENDING' && currentTime < Date.parse(c.deadlineAt)
  const save = async () => {
    setError('')
    try { await api.explainBoarding(c.id, text.trim()); toast('소명을 접수했어요'); onSaved() }
    catch (e) { setError(e.message) }
  }
  return <article className="review-card" aria-label={`일행 ${c.partyId} 탑승 조사`}>
    <div className="review-card-head">
      <h2>일행 {c.partyId}</h2>
      <span className={`badge ${CASE_TONE[c.status] || ''}`}>{CASE_LABEL[c.status] || '상태 확인 중'}</span>
    </div>
    <Rows items={[
      ['소명 마감', formatDateTime(c.deadlineAt)],
      ['일행 표시', `왔어요 ${votes.filter((v) => v.came).length}명 · 안 왔어요 ${votes.filter((v) => !v.came).length}명`],
    ]} />
    {explanation ? <><h3>접수한 소명</h3><p className="review-text">{explanation.explanation}</p><p className="note">첫 소명은 바꿀 수 없어요.</p></>
      : open ? <>
        <label className="field">당시 상황을 알려 주세요<textarea maxLength={2000} value={text} onChange={(e) => setText(e.target.value)} /></label>
        <p className="note">제출한 첫 소명은 바꿀 수 없어요. 상황을 확인한 뒤 제출해 주세요.</p>
        <Btn disabled={!text.trim()} onClick={save}>소명 제출</Btn>
      </> : <p className="note">소명 접수 기간이 끝났어요.</p>}
    {c.decisionReason && <Say title="판정 근거">{c.decisionReason}</Say>}
    {error && <p role="alert">{error}</p>}
  </article>
}

export function BoardingCasesScreen({ now, onBack }) {
  const remote = useRemote(api.boardingCases)
  const reload = remote.reload
  const currentTime = useClock(now)
  useEffect(() => {
    const refresh = () => reload()
    window.addEventListener('taximate:state', refresh)
    const timer = setInterval(refresh, 30000)
    return () => { window.removeEventListener('taximate:state', refresh); clearInterval(timer) }
  }, [reload])
  return <Screen><BackBar title="내 탑승 확인·소명" onBack={onBack} /><div className="pad review-stack">
    <h1 className="head">내 탑승 확인·소명</h1>
    <Say>도착 표시가 없다는 이유만으로 노쇼를 확정하지 않아요. 일행의 확인과 본인 소명을 함께 검토해요.</Say>
    <RemoteState remote={remote} />
    {remote.data?.length === 0 && <p>확인이 필요한 탑승이 없어요.</p>}
    {remote.data?.map((item) => <CaseCard key={item.investigation.id} item={item} currentTime={currentTime} onSaved={remote.reload} />)}
    <Btn className="line" onClick={remote.reload}>목록 새로고침</Btn>
  </div></Screen>
}

export function PartyBoardingPanel({ party, now, showEmpty = false }) {
  const load = useCallback(() => Promise.all([api.partyBoardingCases(party.id), api.compensation(party.id)]), [party.id])
  const remote = useRemote(load)
  const reload = remote.reload
  const currentTime = useClock(now)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(null)
  const previousNow = useRef(now)
  const mine = party.members?.find((m) => m.requestId === party.myRequestId)?.memberId
  useEffect(() => { if (previousNow.current !== now) { previousNow.current = now; reload() } }, [now, reload])
  const [cases = [], entries = []] = remote.data || []
  const candidates = cases.map((item) => ({ ...item, memberId: item.investigation.memberId }))
  const payer = party.members?.find((m) => m.memberId === party.payerId)
  if (!party.startedAt && payer && payer.memberId !== mine && !party.boardedAt && currentTime >= Date.parse(party.departureAt)
    && ['READY', 'RIDE_UNCONFIRMED', 'PAYER_PENDING'].includes(party.status) && !candidates.some((c) => c.memberId === payer.memberId)) {
    candidates.push({ memberId: payer.memberId })
  }
  const vote = async () => {
    setError('')
    try { await api.boardingVote(party.id, pending.memberId, pending.came); setPending(null); remote.reload(); toast('탑승 확인을 접수했어요') }
    catch (e) { setError(e.message) }
  }
  const others = candidates.filter((c) => c.memberId !== mine)
  // 확인할 사람도 보전 내역도 없으면 기본은 숨기되, 직접 눌러 열었을 때는 안내를 보여 준다.
  const empty = !remote.loading && !remote.error && others.length === 0 && entries.length === 0 && !pending && !error
  if (empty && !showEmpty) return null
  return <section className="block review-stack" aria-label="일행 탑승 확인">
    <h2 className="block-title">일행 탑승 확인</h2>
    <p className="note">직접 본 것만 표시해 주세요. 한 번 표시하면 바꿀 수 없어요.</p>
    <RemoteState remote={remote} />
    {empty && <p className="note">확인이 필요한 일행이나 보전 내역이 없어요.</p>}
    {others.map((item) => {
      const c = item.investigation
      const name = item.nickname || party.members?.find((m) => m.memberId === item.memberId)?.nickname || `회원 #${item.memberId}`
      const ownVote = item.votes?.find((v) => v.actorId === mine)
      const open = !c || c.status === 'PENDING' && currentTime < Date.parse(c.deadlineAt)
      return <article className="review-card" key={item.memberId}>
        <h3>{name}</h3>{c && <p>{CASE_LABEL[c.status]} · 마감 {formatDateTime(c.deadlineAt)}</p>}
        {ownVote ? <p>내 표시: {ownVote.came ? '왔어요' : '안 왔어요'}</p> : open && <div className="review-actions">
          {c && <Btn className="small line" onClick={() => setPending({ memberId: item.memberId, came: true, name })}>{name} 왔어요</Btn>}
          <Btn className="small line" onClick={() => setPending({ memberId: item.memberId, came: false, name })}>{name} 안 왔어요</Btn>
        </div>}
      </article>
    })}
    {pending && <div className="review-card" role="group" aria-label="탑승 표시 최종 확인">
      <p>{pending.name}님이 {pending.came ? '왔어요' : '안 왔어요'}로 제출할까요? 제출 후 바꿀 수 없어요.</p>
      <div className="review-actions"><Btn onClick={vote}>표시 제출</Btn><Btn className="line" onClick={() => setPending(null)}>돌아가기</Btn></div>
    </div>}
    {error && <p role="alert">{error}</p>}
    {entries.length > 0 && <div><h3>보전 내역</h3>{entries.map((e) => <p key={e.id}>회원 #{e.memberId} · 보전 {won(e.credit)} · 부담 {won(e.charge)} · 최종 분담금 {won(e.netShare)}</p>)}</div>}
  </section>
}

/** 같은 입력의 재전송은 같은 키를 쓴다. 서버 성공 뒤에만 완료를 알린다. */
function EvidenceAction({ label, act, onSaved, children, disabled = false, identity = '' }) {
  const [evidence, setEvidence] = useState('')
  const [error, setError] = useState('')
  const request = useRef(null)
  const submit = async () => {
    setError('')
    if (!request.current || request.current.evidence !== evidence.trim() || request.current.identity !== identity) request.current = { evidence: evidence.trim(), identity, key: freshKey() }
    try { await act(evidence.trim(), request.current.key); toast('처리 결과를 저장했어요'); onSaved() }
    catch (e) { setError(e.message) }
  }
  return <div className="review-action-form">
    {children}
    <label className="field">처리 근거 (필수)<textarea maxLength={2000} value={evidence} onChange={(e) => setEvidence(e.target.value)} /></label>
    <p className="note">처리 내용과 근거는 관리자 기록에 남아요.</p>
    <Btn disabled={disabled || !evidence.trim()} onClick={submit}>{label}</Btn>
    {error && <p role="alert">{error}</p>}
  </div>
}

function NoShowReview({ item, onSaved }) {
  const id = item.investigation.id
  const load = useCallback(() => api.adminNoShow(id), [id])
  const remote = useRemote(load)
  const [decision, setDecision] = useState('UNDETERMINED')
  return <article className="review-card">
    <div className="review-card-head">
      <h2>노쇼 조사 <span className="review-id">#{id}</span></h2>
      <span className={`badge ${CASE_TONE[item.investigation.status] || 'warn'}`}>{CASE_LABEL[item.investigation.status] || '검토 대기'}</span>
    </div>
    <p className="review-meta-row"><span>일행 {item.investigation.partyId}</span><span>대상 회원 {item.investigation.memberId}</span></p>
    <Rows items={[
      ['부재 표시', `${item.absentVotes}명`], ['참석 표시', `${item.presentVotes}명`],
      ['영수증', item.receiptPresent ? '있음' : '없음'], ['소명', item.explanationPresent ? '있음' : '없음'],
      ['탑승 미확인', formatDateTime(item.boarding?.boardingUnconfirmedAt)],
    ]} />
    <RemoteState remote={remote} />
    {remote.data && <>
      <h3>본인 소명</h3><p className="review-text">{remote.data.explanation?.explanation || '접수된 소명 없음'}</p>
      <Fold title="일행 표시와 최근 대화 확인" className="review-fold">
        {remote.data.votes.map((v) => <p key={v.id} className="review-vote">회원 {v.actorId}: <b>{v.came ? '왔어요' : '안 왔어요'}</b> · {formatDateTime(v.createdAt)}</p>)}
        <ol className="review-chat">{remote.data.chat.map((m) => <li key={m.id}><small>회원 {m.memberId} · {formatDateTime(m.createdAt)}</small>
          {m.kind === 'IMAGE' ? m.imageUrl ? <a className="review-link" href={m.imageUrl} target="_blank" rel="noreferrer"><ExternalLink size={14} aria-hidden="true" />사진 증거 보기</a> : <p>보관 중인 사진이 없어요.</p> : <p>{m.text}</p>}
        </li>)}</ol>
      </Fold>
    </>}
    {item.receiptPresent && <a className="review-link" href={`/api/admin/parties/${item.investigation.partyId}/receipt`} target="_blank" rel="noreferrer"><ExternalLink size={14} aria-hidden="true" />영수증 확인</a>}
    <EvidenceAction identity={decision} label="노쇼 판정 저장" disabled={!remote.data} onSaved={onSaved}
      act={(evidence) => api.decideNoShow(id, { decision, evidence })}>
      <label className="field">판정<select value={decision} onChange={(e) => setDecision(e.target.value)}>
        <option value="UNDETERMINED">판단 불가 · 노쇼 아님으로 처리</option><option value="NOT_NO_SHOW">노쇼 아님</option><option value="NO_SHOW">노쇼 확정</option>
      </select></label>
    </EvidenceAction>
  </article>
}

function FareReview({ item, onSaved }) {
  const [accept, setAccept] = useState(false)
  const [amount, setAmount] = useState(String(item.amount ?? ''))
  const [rideDecision, setRideDecision] = useState('')
  const ride = item.reason === 'RIDE_DISPUTE'
  const held = item.reason === 'RIDE_FINANCIAL_HOLD'
  const validAmount = /^\d+$/.test(amount) && Number.isSafeInteger(Number(amount)) && Number(amount) > 0
  return <article className="review-card">
    <div className="review-card-head">
      <h2>일행 {item.partyId}</h2>
      <span className={`badge ${held ? 'bad' : 'warn'}`}>{held ? '정산 보류' : ride ? '운행 확인' : '요금 이의'}</span>
    </div>
    <Rows items={[['입력 요금', item.amount == null ? '미입력' : won(item.amount)]]} />
    {item.receiptPresent ? <a className="review-link" href={`/api/admin/parties/${item.partyId}/receipt`} target="_blank" rel="noreferrer"><ExternalLink size={14} aria-hidden="true" />영수증 확인</a> : <p className="note">영수증 없음</p>}
    {item.objections.map((o) => <p className="review-text" key={o.id}>이의 사유: {o.reason}</p>)}
    {held ? <>
      <Say tone="stop-tone" title={item.rideDecision === 'OPERATED' ? '운행 판정 저장됨' : item.rideDecision === 'NOT_OPERATED' ? '미운행 판정 저장됨' : '운행 판정 확인 필요'}>결제 내역을 확인할 때까지 이 일행의 자동 정산을 보류해요.</Say>
      {(item.financialConflicts || []).map((order) => <Rows key={order.orderId} items={[
        ['확인할 주문', String(order.orderId)], ['승인 금액', won(order.approvedAmount)],
        ['반영된 환불', won(order.refundedAmount)], ['확인 중인 환불', won(order.pendingRefundAmount)],
      ]} />)}
      {item.recordedPayoutAmount != null && <p>이미 모의 지급 장부에 기록된 금액 {won(item.recordedPayoutAmount)}</p>}
    </> : ride ? <>
      <Say>영수증이 없거나 보관 기한이 지나도 미운행으로 추정하지 않아요. 자료와 소명을 검토해 운행 여부를 직접 선택하고 근거를 남겨 주세요. 운행 확인 시 금액 입력을 복구해요.</Say>
      <EvidenceAction identity={rideDecision} label="운행 검토 확정" disabled={!rideDecision} onSaved={onSaved} act={(evidence) => api.decideRide(item.partyId, rideDecision, evidence)}>
        <label className="field">운행 판정<select aria-label="운행 판정" value={rideDecision} onChange={(e) => setRideDecision(e.target.value)}>
          <option value="">자료를 확인한 뒤 선택해 주세요</option><option value="OPERATED">운행 확인 · 실제 요금 입력 복구</option><option value="NOT_OPERATED">미운행 확인 · 환불 진행</option>
        </select></label>
        </EvidenceAction>
    </> : <EvidenceAction identity={`${accept}:${amount}`} label="요금 판정 저장" onSaved={onSaved} disabled={accept && !validAmount}
      act={(evidence) => api.decideFare(item.partyId, { accept, correctedAmount: accept ? Number(amount) : null, evidence })}>
      <label className="field">요금 이의 판정<select value={accept ? 'accept' : 'reject'} onChange={(e) => setAccept(e.target.value === 'accept')}>
        <option value="reject">기각 · 기존 요금 유지</option><option value="accept">인정 · 실제 요금 수정</option>
      </select></label>
      {accept && <label className="field">확정할 실제 요금<input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} /></label>}
    </EvidenceAction>}
  </article>
}

function MemberRestrictions({ memberId }) {
  const load = useCallback(() => api.adminRestrictions(memberId), [memberId])
  const remote = useRemote(load)
  const [until, setUntil] = useState('')
  const valid = Number.isFinite(Date.parse(until))
  return <div className="review-stack"><h3>회원 #{memberId} 요청 제한</h3><RemoteState remote={remote} />
    {remote.data?.length === 0 && <p>제한 기록 없음</p>}
    {remote.data?.map((r) => <div className="review-card" key={r.id}><p>{r.reason} · 만료 {formatDateTime(r.expiresAt)}</p>
      <EvidenceAction label="요청 제한 해제" onSaved={remote.reload} act={(evidence) => api.releaseRestriction(r.id, evidence)} />
    </div>)}
    <EvidenceAction identity={until} label="요청 제한 등록" disabled={!valid} onSaved={remote.reload}
      act={(evidence, key) => api.restrictMember(memberId, { until: new Date(until).toISOString(), evidence }, key)}>
      <label className="field">제한 만료 (이 기기 시각)<input type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} /></label>
    </EvidenceAction>
  </div>
}

function Reports({ items }) {
  const [selected, setSelected] = useState(null)
  return <>{items.length === 0 && <p>접수된 신고가 없어요.</p>}
    {items.map((r) => <article className="review-card" key={r.id}>
      <div className="review-card-head">
        <h2>신고 <span className="review-id">#{r.id}</span></h2>
        <span className="badge bad">{REPORT_REASON[r.reason] || r.reason}</span>
      </div>
      <p className="review-meta-row"><span>일행 {r.partyId}</span><span>신고자 {r.reporterId} → 대상 {r.reportedId}</span><span>{formatDateTime(r.createdAt)}</span></p>
      <p className="review-text">{r.memo || '메모 없음'}</p>
      <Btn className="line" onClick={() => setSelected(r.reportedId)}>회원 {r.reportedId} 제한 관리</Btn>
    </article>)}
    <label className="field">관리할 회원 번호<input inputMode="numeric" value={selected ?? ''} onChange={(e) => setSelected(/^\d+$/.test(e.target.value) ? e.target.value : null)} /></label>
    {Number.isSafeInteger(Number(selected)) && Number(selected) > 0 && <MemberRestrictions key={selected} memberId={Number(selected)} />}
  </>
}

function HubEditor({ hub, onSaved, onClose }) {
  const [form, setForm] = useState(hub || { name: '', meetingPoint: '', lat: '', lon: '', schoolIds: [], active: true, fieldChecked: false })
  const [schools, setSchools] = useState((hub?.schoolIds || []).join(', '))
  const change = (key, value) => setForm((old) => ({ ...old, [key]: value }))
  const schoolIds = schools.split(',').map((v) => Number(v.trim()))
  const valid = form.name.trim() && form.meetingPoint.trim() && String(form.lat).trim() && String(form.lon).trim()
    && Number.isFinite(Number(form.lat)) && Math.abs(Number(form.lat)) <= 90 && Number.isFinite(Number(form.lon)) && Math.abs(Number(form.lon)) <= 180
    && schoolIds.length > 0 && schoolIds.every((id) => Number.isSafeInteger(id) && id > 0) && new Set(schoolIds).size === schoolIds.length
  return <article className="review-card"><h2>{hub ? '승차 지점 수정' : '승차 지점 등록'}</h2>
    <EvidenceAction identity={JSON.stringify([form, schools])} label="승차 지점 저장" disabled={!valid} onSaved={onSaved}
      act={(evidence, key) => api.saveHub(hub?.id, { hub: { ...form, lat: Number(form.lat), lon: Number(form.lon), schoolIds }, evidence }, key)}>
      <label className="field">지점 이름<input maxLength={100} value={form.name} onChange={(e) => change('name', e.target.value)} /></label>
      <label className="field">정확히 만날 자리<input maxLength={255} value={form.meetingPoint} onChange={(e) => change('meetingPoint', e.target.value)} /></label>
      <div className="review-grid"><label className="field">위도<input inputMode="decimal" value={form.lat} onChange={(e) => change('lat', e.target.value)} /></label>
        <label className="field">경도<input inputMode="decimal" value={form.lon} onChange={(e) => change('lon', e.target.value)} /></label></div>
      <label className="field">학교 번호 (여러 곳이면 쉼표로 구분)<input value={schools} onChange={(e) => setSchools(e.target.value)} /></label>
      <label className="check"><input type="checkbox" checked={form.active} onChange={(e) => change('active', e.target.checked)} /> 사용 중</label>
      <label className="check"><input type="checkbox" checked={form.fieldChecked} onChange={(e) => change('fieldChecked', e.target.checked)} /> 현장에서 택시 승차 가능 여부 확인함</label>
    </EvidenceAction><Btn className="line" onClick={onClose}>편집 닫기</Btn>
  </article>
}

function Hubs({ items, onSaved }) {
  const [editing, setEditing] = useState(null)
  return <><Btn className="line" onClick={() => setEditing({})}>승차 지점 등록</Btn>
    {editing && <HubEditor key={editing.id || 'new'} hub={editing.id ? editing : null} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); onSaved() }} />}
    {items.map((h) => <article className="review-card" key={h.id}>
      <div className="review-card-head">
        <h2>{h.name} <span className="review-id">#{h.id}</span></h2>
        <div className="review-badges">
          <span className={`badge ${h.active ? 'ok' : ''}`}>{h.active ? '사용 중' : '사용 중지'}</span>
          <span className={`badge ${h.fieldChecked ? 'ok' : 'warn'}`}>{h.fieldChecked ? '현장 확인됨' : '현장 미확인'}</span>
        </div>
      </div>
      <p>{h.meetingPoint}</p>
      <p className="note">연결 학교 {h.schoolIds.join(', ')} · 좌표 {h.lat}, {h.lon}</p>
      <Btn className="line" onClick={() => setEditing(h)}>지점 수정</Btn>
    </article>)}</>
}

const ADMIN_TABS = [['no-shows', '노쇼 검토', () => api.adminNoShows()], ['fare', '요금·운행', () => api.adminFareReviews()],
  ['reports', '신고·제한', () => api.adminReports()], ['overdue', '미정산', () => api.adminOverdue()], ['reserve', '적립금', () => api.adminReserve()], ['hubs', '승차 지점', () => api.adminHubs()], ['audits', '처리 기록', () => api.adminAudits()]]

const ADMIN_TAB_DESC = {
  'no-shows': '소명, 일행 표시, 최근 대화를 대조해 노쇼 여부를 판정해요. 판단 불가 시 면책 처리돼요.',
  fare: '실제 요금과 영수증을 대조하고, 운행 분쟁 또는 요금 이의를 판정해요.',
  reports: '신고된 회원의 이용 제한 기간을 조회하고 해제하거나 새 제한을 등록해요.',
  overdue: '결제 기한이 지난 후불·보전·추가 분담금을 확인하고 안내 이력을 남겨요.',
  reserve: 'v2 확정 분담금 방식의 정산 적립금 잔액과 보전 내역을 조회해요.',
  hubs: '캠퍼스 거점 위치와 현장 승차 가능 여부를 관리해요.',
  audits: '관리자 판정과 처리 근거 이력을 최신 순으로 감사 조회해요.',
}

/** 미정산 주문의 결제 목적. 후불 정산 v3에서는 후불·보전·추가 분담금이 함께 쌓인다. */
const OVERDUE_PURPOSE = { POSTPAID: '후불 분담금', COMPENSATION: '이탈 보전금', ADDITIONAL: '추가 정산금' }

/** 후불 정산 v3는 정산 적립금을 쓰지 않으므로 적립금 탭을 감춘다. 설정을 모르면 기존 탭을 그대로 둔다. */
const adminTabsFor = (config) => (Number(config?.paymentFlowVersion) >= 3 ? ADMIN_TABS.filter(([id]) => id !== 'reserve') : ADMIN_TABS)

function AdminList({ tab }) {
  const remote = useRemote(ADMIN_TABS.find(([id]) => id === tab)[2])
  const [query, setQuery] = useState('')
  const q = query.trim().toLowerCase()
  const filterBy = (list) => (q && Array.isArray(list)
    ? list.filter((item) => JSON.stringify(item).toLowerCase().includes(q))
    : list)
  const items = filterBy(remote.data)
  return <div className="review-stack"><RemoteState remote={remote} />
    {Array.isArray(remote.data) && remote.data.length > 0 && (
      <div className="admin-filter-row">
        <label className="sr-only" htmlFor="admin-filter">목록 검색</label>
        <input id="admin-filter" type="search" value={query} placeholder="일행 번호, 회원 번호, 지점명, 내용 검색" onChange={(e) => setQuery(e.target.value)} />
      </div>
    )}
    {remote.data && <>
      {(Array.isArray(items) && items.length === 0) && (!['reports', 'hubs'].includes(tab) || q) && <p>{q ? '검색 조건에 맞는 항목이 없어요.' : '처리할 항목이 없어요.'}</p>}
      {tab === 'no-shows' && items.map((c) => <NoShowReview key={c.investigation.id} item={c} onSaved={remote.reload} />)}
      {tab === 'fare' && items.map((r) => <FareReview key={r.partyId} item={r} onSaved={remote.reload} />)}
      {tab === 'reports' && <Reports items={items} />}
      {tab === 'hubs' && <Hubs items={items} onSaved={remote.reload} />}
      {tab === 'overdue' && items.map((o) => <article className="review-card" key={o.id}>
        <div className="review-card-head">
          <h2>일행 {o.partyId} · 회원 {o.memberId}</h2>
          <span className="review-badges">
            {OVERDUE_PURPOSE[o.purpose] && <span className="badge">{OVERDUE_PURPOSE[o.purpose]}</span>}
            <span className={`badge ${o.adminHandledAt ? 'ok' : 'bad'}`}>{o.adminHandledAt ? '확인 완료' : '미정산'}</span>
          </span>
        </div>
        <Rows items={[['분담금', won(o.amount)], ['결제 기한', formatDateTime(o.deadlineAt)]]} />
        {o.adminHandledAt ? <Say tone="save-tone" title="관리자 확인 완료">{o.adminNote} · {formatDateTime(o.adminHandledAt)}</Say>
          : <EvidenceAction label="미정산 확인 기록" onSaved={remote.reload} act={(evidence) => api.handleOverdue(o.id, evidence)} />}
        <p className="note">확인 기록은 결제 승인이나 채무 면제가 아니에요.</p>
      </article>)}
      {tab === 'reserve' && <article className="review-card" aria-label="정산 적립금">
        <div className="review-card-head">
          <h2>정산 적립금</h2>
          <span className="badge ok">{won(remote.data.balance)}</span>
        </div>
        <Rows items={[['누적 정산', `${remote.data.settlements}건`], ['부족분 보전 합계', won(remote.data.covered)]]} />
        <p className="note">각자 확정 분담금만 내고, 실제 요금과의 차이를 여기서 맡아요. 초기 기금은 운영 주체가 따로 더해 관리해요.</p>
        {remote.data.recent?.length > 0 && <Rows items={remote.data.recent.map((e) => ['일행 ' + e.partyId, (e.amount >= 0 ? '+' : '') + won(e.amount) + ' (확정 ' + won(e.fixedTotal) + ' / 실제 ' + won(e.paidAmount) + ')'])} />}
      </article>}
      {tab === 'audits' && [...items].reverse().map((a) => <article className="review-card" key={a.id}>
        <div className="review-card-head">
          <h2>{AUDIT_ACTION[a.action] || a.action} <span className="review-id">#{a.id}</span></h2>
          {a.decision && <span className="badge">{AUDIT_DECISION[a.decision] || a.decision}</span>}
        </div>
        <p className="review-meta-row">
          <span>{AUDIT_TARGET[a.targetType] || a.targetType} #{a.targetId}</span>
          <span>관리자 {a.actorId}</span>
          <span>{formatDateTime(a.createdAt)}</span>
        </p>
        <p className="review-text">{a.evidence}</p>
      </article>)}
    </>}
    <Btn className="line" onClick={remote.reload}>목록 새로고침</Btn>
  </div>
}

export function AdminScreen({ allowed, config, onBack }) {
  const [selected, setTab] = useState('no-shows')
  if (!allowed) return <Screen><BackBar title="관리자" onBack={onBack} /><div className="pad"><Say tone="stop-tone">관리자만 이용할 수 있어요.</Say></div></Screen>
  const tabs = adminTabsFor(config)
  // 설정이 늦게 도착해 선택한 탭이 사라지면 첫 탭을 보여 준다.
  const tab = tabs.some(([id]) => id === selected) ? selected : tabs[0][0]
  const currentTitle = tabs.find(([id]) => id === tab)?.[1] || '관리자'
  return (
    <AdminLayout tabs={tabs} activeTab={tab} onSelectTab={setTab} onBack={onBack}>
      <div className="review-stack admin-page">
        <div>
          <h1 className="head">{currentTitle}</h1>
          <p className="sub">{ADMIN_TAB_DESC[tab] || '자료를 확인하고 처리 근거를 남겨 주세요.'}</p>
        </div>
        <AdminList key={tab} tab={tab} />
      </div>
    </AdminLayout>
  )
}

export function BoardingPanel({ party, now, showEmpty = false }) {
  return isPaymentParty(party) ? <PartyBoardingPanel key={party.id} party={party} now={now} showEmpty={showEmpty} /> : null
}
