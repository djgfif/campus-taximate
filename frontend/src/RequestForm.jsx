import React, { useEffect, useMemo, useState } from 'react'
import { api } from './api.js'
import { formatDateTime } from './format.js'
import MapPicker from './MapPicker.jsx'

const SLOT_MS = 5 * 60 * 1000
const LEAD_MS = 30 * 60 * 1000
const WINDOW_MINUTES = [60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5]

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
  const [point, setPoint] = useState(null)
  const [name, setName] = useState('')
  const [memo, setMemo] = useState('')

  useEffect(() => {
    api.hubs().then((list) => {
      setHubs(list)
      if (list[0]) setHubId(list[0].id)
    })
  }, [])

  const hub = hubs.find((item) => item.id === hubId)
  const windowStart = options.includes(start) ? start : options[0]
  const windowEnd = new Date(new Date(windowStart).getTime() + length * 60000).toISOString()
  const samples = config?.sampleDropoffs || []

  const pickOnMap = (lat, lon) => {
    setPoint({ lat, lon })
    setName((current) => current || '지도에서 고른 지점')
  }

  const pickSample = (event) => {
    const sample = samples[Number(event.target.value)]
    if (!sample) return
    setPoint({ lat: sample.lat, lon: sample.lon })
    setName(sample.name)
  }

  const submit = (event) => {
    event.preventDefault()
    run(() =>
      api.createRequest({
        hubId,
        windowStart,
        windowEnd,
        dropoffName: name,
        dropoffLat: point.lat,
        dropoffLon: point.lon,
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
            {hubs.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>
        <p className="field">하차 지점 <span className="muted">지도를 눌러 고르세요</span></p>
        <MapPicker hub={hub} value={point} onPick={pickOnMap} />
        {config?.demo && (
          <label>시연용 샘플 지점
            <select defaultValue="" onChange={pickSample}>
              <option value="" disabled>샘플에서 고르기</option>
              {samples.map((item, index) => <option key={item.name} value={index}>{item.name}</option>)}
            </select>
          </label>
        )}
        <label>하차 지점 이름 (일행에게 보입니다)
          <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} placeholder="예: 의정부역 1번 출구" />
        </label>
        <label>출발 가능 시작
          <select value={windowStart} onChange={(e) => setStart(e.target.value)}>
            {options.map((option) => <option key={option} value={option}>{formatDateTime(option)}</option>)}
          </select>
        </label>
        <label>출발 가능 시간
          <select value={length} onChange={(e) => setLength(Number(e.target.value))}>
            {WINDOW_MINUTES.map((minutes) => <option key={minutes} value={minutes}>{minutes}분</option>)}
          </select>
        </label>
        <label>인상착의 메모 (일행에게만 보입니다)
          <input maxLength={100} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="예: 회색 후드" />
        </label>
        <button type="submit" disabled={!hubId || !point}>요청하기</button>
      </form>
    </section>
  )
}
