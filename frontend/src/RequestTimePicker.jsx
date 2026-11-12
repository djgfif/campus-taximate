import React from 'react'
import { Btn, Chips } from './ui.jsx'

const seoulParts = (value) => new Date(new Date(value).getTime() + 9 * 3600000).toISOString().slice(0, 16)

/** 서버가 준 시각에서 만든 유효한 선택지만 제공한다. 브라우저 시간대는 사용하지 않는다. */
export default function RequestTimePicker({ label, options, value, now, onChange }) {
  if (!options.length) return <p role="status">선택할 수 있는 시각이 없어요.</p>
  const chosen = options.includes(value) ? value : options[0]
  const parts = seoulParts(chosen)
  const today = seoulParts(now).slice(0, 10)
  const days = [...new Set(options.map((t) => seoulParts(t).slice(0, 10)))]
  const dayOptions = options.filter((t) => seoulParts(t).startsWith(parts.slice(0, 10)))
  const hours = [...new Set(dayOptions.map((t) => seoulParts(t).slice(11, 13)))]
  const minutes = dayOptions.filter((t) => seoulParts(t).slice(11, 13) === parts.slice(11, 13))
  const chooseDay = (day) => onChange(options.find((t) => seoulParts(t) === day + parts.slice(10)) || options.find((t) => seoulParts(t).startsWith(day)))
  const chooseHour = (hour) => onChange(dayOptions.find((t) => seoulParts(t).slice(11, 16) === hour + parts.slice(13)) || dayOptions.find((t) => seoulParts(t).slice(11, 13) === hour))
  const quick = (minutes) => options.find((t) => new Date(t).getTime() >= new Date(now).getTime() + minutes * 60000)
  return <fieldset className="time-picker" aria-label={label}>
    <legend>{label}</legend>
    <Chips label={label + ' 날짜'} value={parts.slice(0, 10)} onChange={chooseDay}
      options={days.map((day) => ({ value: day, label: day === today ? '오늘' : '내일' }))} />
    <div className="time-columns">
      <label className="field">{label} 시<select value={parts.slice(11, 13)} onChange={(e) => chooseHour(e.target.value)}>{hours.map((h) => <option key={h} value={h}>{Number(h)}시</option>)}</select></label>
      <label className="field">{label} 분<select value={chosen} onChange={(e) => onChange(e.target.value)}>{minutes.map((t) => <option key={t} value={t}>{seoulParts(t).slice(14, 16)}분</option>)}</select></label>
    </div>
    <div className="btns" role="group" aria-label={label + ' 빠른 선택'}>{[30, 60, 120].map((m) => <Btn className="small line" key={m} disabled={!quick(m)} onClick={() => onChange(quick(m))}>{m === 30 ? '30분 뒤' : m / 60 + '시간 뒤'}</Btn>)}</div>
  </fieldset>
}
