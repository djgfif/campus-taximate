import React, { useEffect, useId, useState } from 'react'
import { api } from './api.js'
import { Btn, Sheet, toast } from './ui.jsx'

const REASONS = [['RUDE', '불쾌한 언행'], ['DANGEROUS', '위험 행동'], ['OTHER', '기타']]

export function ReportSheet({ partyId, member, onClose, onSaved }) {
  const [reason, setReason] = useState('RUDE')
  const [memo, setMemo] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const radioName = useId()
  const act = async (action, message) => {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await action()
      toast(message)
      onSaved()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Sheet title={`${member.nickname}님 신고·다시 매칭 안 함`} onClose={onClose}>
      <p className="note">신고하면 다시 매칭하지 않도록 함께 지정돼요. 다시 매칭 안 함은 상대에게 알리지 않아요.</p>
      <form className="report-form" onSubmit={(e) => {
        e.preventDefault()
        act(() => api.reportMember(partyId, member.memberId, {
          reason, memo, ...(member.messageId == null ? {} : { messageId: member.messageId }),
        }), '신고를 접수했어요')
      }}>
        <fieldset className="report-reasons" disabled={busy}>
          <legend>신고 사유</legend>
          {REASONS.map(([value, label]) => (
            <label key={value}>
              <input type="radio" name={radioName} value={value} checked={reason === value} onChange={() => setReason(value)} />
              {label}
            </label>
          ))}
        </fieldset>
        <label className="field">메모 (선택, 200자 이하)
          <textarea maxLength={200} value={memo} disabled={busy} onChange={(e) => setMemo(e.target.value)} />
        </label>
        <Btn type="submit" disabled={busy}>신고하기</Btn>
      </form>
      <Btn className="line" disabled={busy} onClick={() => act(
        () => member.avoided ? api.unavoidMember(member.memberId) : api.avoidMember(member.memberId),
        member.avoided ? '다시 매칭 안 함을 해제했어요' : '앞으로 같은 일행으로 매칭하지 않아요',
      )}>{member.avoided ? '다시 매칭 안 함 해제' : '다시 매칭하지 않기'}</Btn>
      {error && <p role="alert">{error}</p>}
      <Btn className="quiet" disabled={busy} onClick={onClose}>닫기</Btn>
    </Sheet>
  )
}

/** 이 API는 요청한 본인을 제외하고, 본인이 지정한 회피 여부만 돌려준다. */
export function MemberActions({ partyId, onlyAvoid = false, onSaved }) {
  const [peers, setPeers] = useState(null)
  const [selected, setSelected] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let closed = false
    api.partyMembers(partyId).then((list) => {
      if (!closed) { setPeers(list); setError('') }
    }).catch((e) => { if (!closed) setError(e.message) })
    return () => { closed = true }
  }, [partyId, reload])
  const saved = () => {
    setSelected(null)
    setReload((value) => value + 1)
    onSaved?.()
  }
  const avoid = async (member) => {
    setBusy(true)
    setError('')
    try {
      await api.avoidMember(member.memberId)
      toast('앞으로 같은 일행으로 매칭하지 않아요')
      saved()
    } catch (e) { setError(e.message) } finally { setBusy(false) }
  }
  return (
    <section className="block" aria-label="일행원 신고와 매칭 설정">
      <h2 className="block-title">{onlyAvoid ? '다시 매칭하지 않을 상대를 골라 주세요' : '일행원 신고·다시 매칭 안 함'}</h2>
      {!peers && !error && <p role="status">일행원을 불러오고 있어요</p>}
      <div className="list">
        {peers?.map((member) => (
          <div className="item" key={member.memberId}>
            <b>{member.nickname}</b>
            <Btn className="small line" disabled={busy || (onlyAvoid && member.avoided)}
              aria-label={`${member.nickname}님 ${member.avoided && !onlyAvoid ? '다시 매칭 안 함 해제' : onlyAvoid ? '다시 매칭하지 않기' : '신고·다시 매칭 안 함'}`}
              onClick={() => onlyAvoid ? avoid(member) : setSelected(member)}>
              {member.avoided ? (onlyAvoid ? '이미 지정했어요' : '다시 매칭 안 함 해제') : onlyAvoid ? '다시 매칭하지 않기' : '신고·다시 매칭 안 함'}
            </Btn>
          </div>
        ))}
      </div>
      {error && <p role="alert">{error} <button type="button" onClick={() => setReload((value) => value + 1)}>다시 불러오기</button></p>}
      {selected && <ReportSheet partyId={partyId} member={selected} onClose={() => setSelected(null)} onSaved={saved} />}
    </section>
  )
}

export function HistoryMemberActions({ partyId }) {
  const [open, setOpen] = useState(false)
  return (
    <div>
      <Btn className="small quiet" aria-expanded={open} onClick={() => setOpen((value) => !value)}>일행원 신고·다시 매칭 안 함</Btn>
      {open && <MemberActions partyId={partyId} />}
    </div>
  )
}

export function SettledFeedback({ party, myId }) {
  const storageKey = `taximate:ride-feedback:v1:${myId}:${party.id}`
  const [closed, setClosed] = useState(() => {
    try { return localStorage.getItem(storageKey) === 'closed' } catch { return false }
  })
  const [choosing, setChoosing] = useState(false)
  const close = () => {
    setClosed(true)
    setChoosing(false)
    try { localStorage.setItem(storageKey, 'closed') } catch { /* 저장을 막은 환경에서도 카드는 닫힌다. */ }
  }
  if (closed || !['COMPLETED', 'SETTLED'].includes(party.status)) return null
  return (
    <section className="block" aria-label="정산 완료 후 매칭 설정">
      <h2 className="block-title">이번 일행과 다음에도 함께할까요?</h2>
      <Btn className="line" onClick={close}>다음에도 같이 타도 괜찮아요</Btn>
      <Btn className="quiet" onClick={() => setChoosing(true)}>다시 매칭하지 않기</Btn>
      {choosing && (
        <Sheet title="다시 매칭하지 않을 상대" onClose={() => setChoosing(false)}>
          <MemberActions partyId={party.id} onlyAvoid onSaved={close} />
          <Btn className="quiet" onClick={() => setChoosing(false)}>닫기</Btn>
        </Sheet>
      )}
    </section>
  )
}
