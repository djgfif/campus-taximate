import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { Avatar, Btn, Sheet, toast } from './ui.jsx'

export const PRAISE = [['ON_TIME', '시간 약속을 잘 지켜요'], ['KIND', '친절해요'], ['QUIET', '편하게 갈 수 있었어요'], ['CLEAR_PAY', '정산이 깔끔해요']]
const praiseLabel = Object.fromEntries(PRAISE)

/** 같이 타는 사람의 신뢰 정보(학과·학번, 탄 횟수, 노쇼 여부, 받은 칭찬). 일행원만 읽을 수 있다. */
export function usePeople(partyId, now) {
  const [people, setPeople] = useState([])
  useEffect(() => {
    let closed = false
    api.partyPeople?.(partyId)?.then((list) => !closed && setPeople(list || [])).catch(() => !closed && setPeople([]))
    return () => { closed = true }
  }, [partyId, now])
  return [people, setPeople]
}

/** "컴퓨터공학과 · 22학번 · 5번 탔어요 · 칭찬 3" 같은 한 줄. 처음 타는 사람은 그렇게 알려 준다. */
export function trustLine(person) {
  if (!person) return ''
  const parts = []
  if (person.profile) parts.push(person.profile)
  parts.push(person.rides > 0 ? `${person.rides}번 탔어요` : '처음 타요')
  if (person.rides > 0 && person.noShowFree) parts.push('노쇼 없음')
  if (person.praises > 0) parts.push(`칭찬 ${person.praises}`)
  return parts.join(' · ')
}

/** 운행이 끝난 뒤 같이 탄 사람에게 칭찬을 남긴다. 나쁜 평가는 없고, 문제는 신고로 처리한다. */
export function PraiseSection({ partyId, now }) {
  const [people, setPeople] = usePeople(partyId, now)
  const [target, setTarget] = useState(null)
  const others = people.filter((p) => !p.me)
  if (others.length === 0) return null
  return (
    <section className="praise" aria-label="같이 탄 사람 칭찬">
      <h2 className="block-title">같이 탄 사람에게 고마움 전하기</h2>
      <ul>
        {others.map((p) => (
          <li key={p.memberId}>
            <Avatar name={p.nickname} size={36} />
            <span><b>{p.nickname}</b><small>{p.myTags.length ? p.myTags.map((t) => praiseLabel[t]).join(', ') : trustLine(p)}</small></span>
            <Btn className="small line" onClick={() => setTarget(p)}>{p.myTags.length ? '더 남기기' : '칭찬하기'}</Btn>
          </li>
        ))}
      </ul>
      {target && <PraiseSheet partyId={partyId} person={target} onClose={() => setTarget(null)} onSaved={(list) => { setPeople(list); setTarget(null) }} />}
    </section>
  )
}

function PraiseSheet({ partyId, person, onClose, onSaved }) {
  const [tags, setTags] = useState(person.myTags || [])
  const toggle = (tag) => setTags((list) => (list.includes(tag) ? list.filter((t) => t !== tag) : [...list, tag]))
  const save = async () => {
    try {
      const list = await api.praise(partyId, person.memberId, tags)
      toast(`${person.nickname}님에게 칭찬을 남겼어요`)
      onSaved(list)
    } catch (e) { toast(e.message, 'bad') }
  }
  return (
    <Sheet title={`${person.nickname}님은 어땠나요?`} onClose={onClose}>
      <div className="praise-tags" role="group" aria-label="칭찬 고르기">
        {PRAISE.map(([tag, label]) => (
          <button key={tag} type="button" aria-pressed={tags.includes(tag)} className={tags.includes(tag) ? 'chip on' : 'chip'} onClick={() => toggle(tag)}>{label}</button>
        ))}
      </div>
      <p className="note">칭찬은 상대 프로필에 숫자로만 쌓여요. 불편했던 일은 신고로 알려 주세요.</p>
      <Btn disabled={tags.length === 0} onClick={save}>남기기</Btn>
    </Sheet>
  )
}

const YEARS = Array.from({ length: 12 }, (_, i) => 26 - i)

/** 내 공개 프로필: 학과·학번. 끄면 일행에게 별명만 보인다. */
export function ProfileFields({ value, onChange }) {
  return <>
    <label className="field">학과 (선택)<input maxLength={40} value={value.department || ''} placeholder="예: 컴퓨터공학과"
      onChange={(e) => onChange({ ...value, department: e.target.value })} /></label>
    <label className="field">학번 (선택)<select value={value.admissionYear ?? ''} onChange={(e) => onChange({ ...value, admissionYear: e.target.value === '' ? null : Number(e.target.value) })}>
      <option value="">고르지 않음</option>{YEARS.map((y) => <option key={y} value={y}>{String(y).padStart(2, '0')}학번</option>)}
    </select></label>
    <label className="check"><input type="checkbox" checked={value.showProfile !== false} onChange={(e) => onChange({ ...value, showProfile: e.target.checked })} />같이 타는 사람에게 학과·학번 보여 주기</label>
  </>
}

export function ProfileSheet({ onClose }) {
  const [value, setValue] = useState(null)
  useEffect(() => { api.myProfile().then(setValue).catch(() => setValue({ showProfile: true })) }, [])
  const save = async () => {
    try { await api.saveProfile(value); toast('프로필을 저장했어요'); onClose() } catch (e) { toast(e.message, 'bad') }
  }
  return (
    <Sheet title="내 프로필" onClose={onClose}>
      {!value ? <p role="status">불러오는 중…</p> : <>
        <p className="sub">같이 타는 사람이 나를 알아볼 수 있게 해 주세요. 학과·학번은 같은 방향 학생과 일행에게만 보여요.</p>
        <ProfileFields value={value} onChange={setValue} />
        <Btn onClick={save}>저장</Btn>
      </>}
    </Sheet>
  )
}
