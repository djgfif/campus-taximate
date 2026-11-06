import React, { useEffect, useMemo, useState } from 'react'
import { api } from './api.js'
import { formatDateTime } from './format.js'

const SLOT_MS = 5 * 60 * 1000
const LEAD_MS = 30 * 60 * 1000

/** 지금 이후의 5분 단위 시각을 고를 수 있게 만든다. */
function slotOptions(now) {
  const first = Math.ceil(new Date(now).getTime() / SLOT_MS) * SLOT_MS + LEAD_MS
  return Array.from({ length: 36 }, (_, i) => new Date(first + i * SLOT_MS).toISOString())
}

export default function RequestForm({ config, now, run }) {
  const [hubs, setHubs] = useState([])
  const [hubId, setHubId] = useState('')
  const options = useMemo(() => slotOptions(now), [Math.floor(new Date(now).getTime() / SLOT_MS)])
  const [start, setStart] = useState('')
  const [length, setLength] = useState(60)
  const [dropoff, setDropoff] = useState(0)
  const [memo, setMemo] = useState('')

  useEffect(() => {
    api.hubs().then((list) => {
      setHubs(list)
      if (list[0]) setHubId(list[0].id)
    })
  }, [])

  const windowStart = options.includes(start) ? start : options[0]
  const windowEnd = new Date(new Date(windowStart).getTime() + length * 60000).toISOString()
  const dropoffs = config?.sampleDropoffs || []
  const target = dropoffs[dropoff]

  const submit = (event) => {
    event.preventDefault()
    run(() =>
      api.createRequest({
        hubId,
        windowStart,
        windowEnd,
        dropoffName: target.name,
        dropoffLat: target.lat,
        dropoffLon: target.lon,
        memo,
      }),
    )
  }

  return (
    <section className="card">
      <h2>탑승 요청</h2>
      <form onSubmit={submit}>
        <label>출발 거점
          <select value={hubId} onChange={(e) => setHubId(Number(e.target.value))}>
            {hubs.map((hub) => <option key={hub.id} value={hub.id}>{hub.name}</option>)}
          </select>
        </label>
        <label>하차 지점
          <select value={dropoff} onChange={(e) => setDropoff(Number(e.target.value))}>
            {dropoffs.map((item, index) => <option key={item.name} value={index}>{item.name}</option>)}
          </select>
        </label>
        <label>출발 가능 시작
          <select value={windowStart} onChange={(e) => setStart(e.target.value)}>
            {options.map((option) => <option key={option} value={option}>{formatDateTime(option)}</option>)}
          </select>
        </label>
        <label>출발 가능 시간
          <select value={length} onChange={(e) => setLength(Number(e.target.value))}>
            {[60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5].map((minutes) => <option key={minutes} value={minutes}>{minutes}분</option>)}
          </select>
        </label>
        <label>인상착의 메모 (일행에게만 보입니다)
          <input maxLength={100} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="예: 회색 후드" />
        </label>
        <button type="submit" disabled={!hubId || !target}>요청하기</button>
      </form>
    </section>
  )
}
