import React, { useEffect, useMemo, useState } from 'react'
import { api } from './api.js'
import { formatDateTime, formatTime, won } from './format.js'
import MapPicker, { searchPlaces } from './MapPicker.jsx'
import { BackBar, Chips, Rows, Screen } from './ui.jsx'

const REAL_NAME = '택시비를 송금할 때 서로의 실명이 표시될 수 있습니다.'
const SLOT_MS = 5 * 60 * 1000
const LEAD_MS = 30 * 60 * 1000
const MIN_ARRIVAL_MS = 3 * 60 * 60 * 1000

function startOptions(now) {
  const first = Math.ceil(new Date(now).getTime() / SLOT_MS) * SLOT_MS + LEAD_MS
  return Array.from({ length: 36 }, (_, i) => new Date(first + i * SLOT_MS).toISOString())
}

function arrivalOptions(now) {
  const first = Math.ceil((new Date(now).getTime() + MIN_ARRIVAL_MS) / SLOT_MS) * SLOT_MS
  return Array.from({ length: 288 }, (_, i) => new Date(first + i * SLOT_MS).toISOString())
}

/** 다음에 오는 그 시각. 일행을 찾을 시간이 없으면 다음 날로 넘긴다. */
function nextClock(now, hour, minute) {
  const date = new Date(now)
  date.setHours(hour, minute, 0, 0)
  if (date.getTime() < new Date(now).getTime() + MIN_ARRIVAL_MS) date.setDate(date.getDate() + 1)
  return date.toISOString()
}

/** 입력이 갖춰지면 단독 요금과 결과 안내 시각을 미리 받는다. */
function usePreview(body) {
  const key = body ? JSON.stringify(body) : ''
  const [result, setResult] = useState({})
  useEffect(() => {
    setResult({})
    if (!key) return undefined
    let closed = false
    const timer = setTimeout(() => {
      api.previewRequest(JSON.parse(key))
        .then((data) => !closed && setResult({ data }))
        .catch((e) => !closed && setResult({ error: e.message }))
    }, 300)
    return () => {
      closed = true
      clearTimeout(timer)
    }
  }, [key])
  return result
}

function PlaceField({ title, hint, center, value, config, onChange, mapLabel, placeholder }) {
  const [results, setResults] = useState([])
  const samples = config?.sampleDropoffs || []

  useEffect(() => {
    if (value.fromList) return undefined
    let closed = false
    const timer = setTimeout(() => {
      searchPlaces(value.name, center).then((list) => !closed && setResults(list)).catch(() => setResults([]))
    }, 300)
    return () => {
      closed = true
      clearTimeout(timer)
    }
  }, [value.name])

  const choose = (place) => {
    setResults([])
    onChange({ name: place.name, point: { lat: place.lat, lon: place.lon }, fromList: true })
  }

  return (
    <>
      <label>{title}
        <input required maxLength={100} value={value.name} placeholder={placeholder}
          onChange={(e) => onChange({ name: e.target.value, point: null, fromList: false })} />
      </label>
      {results.length > 0 && (
        <ul className="places">
          {results.map((place) => (
            <li key={place.id}>
              <button type="button" onClick={() => choose(place)}>
                <b>{place.name}</b><small>{place.address}</small>
              </button>
            </li>
          ))}
        </ul>
      )}
      <MapPicker center={center} value={value.point} label={mapLabel}
        onPick={(lat, lon) => onChange({
          name: value.point || !value.name ? '지도에서 고른 지점' : value.name,
          point: { lat, lon },
          fromList: true,
        })} />
      {config?.demo && (
        <label>시연용 샘플 지점
          <select value="" onChange={(e) => choose({ ...samples[Number(e.target.value)] })}>
            <option value="" disabled>샘플에서 고르기</option>
            {samples.map((item, index) => <option key={item.name} value={index}>{item.name}</option>)}
          </select>
        </label>
      )}
      {value.name && !value.point && <p className="muted">목록이나 지도에서 위치를 골라 주세요.</p>}
      <p className="muted">{hint}</p>
    </>
  )
}

function MemoField({ value, onChange }) {
  return (
    <label>일행이 알아볼 메모 (선택)
      <input maxLength={100} value={value} onChange={(e) => onChange(e.target.value)} placeholder="검은 가방" />
    </label>
  )
}

const pointBody = (place) => ({ pointName: place.name, pointLat: place.point?.lat, pointLon: place.point?.lon })

function initialPlace(initial) {
  return initial
    ? { name: initial.pointName, point: { lat: initial.pointLat, lon: initial.pointLon }, fromList: true }
    : { name: '', point: null, fromList: false }
}

export function HomeRequest({ initial, config, now, run, onClose }) {
  const [step, setStep] = useState(initial ? 2 : 1)
  const [hubs, setHubs] = useState([])
  const [hubId, setHubId] = useState(initial?.hubId || null)
  const [place, setPlace] = useState(initialPlace(initial))
  const options = useMemo(() => startOptions(now), [Math.floor(new Date(now).getTime() / SLOT_MS)])
  const [start, setStart] = useState('')
  const [choice, setChoice] = useState('first')
  const [length, setLength] = useState(60)
  const [memo, setMemo] = useState(initial?.memo || '')

  useEffect(() => {
    api.hubs().then((list) => {
      setHubs(list)
      setHubId((current) => current || list[0]?.id || null)
    })
  }, [])

  const hub = hubs.find((item) => item.id === hubId)
  const windowStart = choice === 'first' ? options[0] : choice === 'later' ? options[6]
    : options.includes(start) ? start : options[0]
  const windowEnd = new Date(new Date(windowStart).getTime() + length * 60000).toISOString()
  const body = { direction: 'FROM_HUB', hubId, windowStart, windowEnd, memo, ...pointBody(place) }
  const preview = usePreview(step === 2 && hubId && place.point ? body : null)

  if (step === 1) {
    return (
      <Screen actions={<button disabled={!place.point || !place.name} onClick={() => setStep(2)}>다음</button>}>
        <BackBar onBack={onClose} step="하교 1 / 2" />
        <h2>어디서 내리세요?</h2>
        <label>출발 거점
          <select value={hubId || ''} onChange={(e) => setHubId(Number(e.target.value))}>
            {hubs.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>
        <PlaceField title="하차 지점" center={hub} value={place} config={config} onChange={setPlace}
          mapLabel="하차 지점 지도" placeholder="건물이나 정류장 이름"
          hint="일행에게는 하차 지점 이름만 보입니다. 집 주소 대신 근처 건물이나 정류장을 고르세요." />
      </Screen>
    )
  }

  return (
    <Screen actions={<button disabled={!preview.data} onClick={() => run(async () => { await api.createRequest(body); onClose() })}>일행 찾기</button>}>
      <BackBar onBack={() => (initial ? onClose() : setStep(1))} step="하교 2 / 2" />
      <h2>언제 출발할 수 있어요?</h2>
      <Chips value={choice} onChange={setChoice} options={[
        { value: 'first', label: formatTime(options[0]) },
        { value: 'later', label: formatTime(options[6]) },
        { value: 'other', label: '다른 시각' },
      ]} />
      {choice === 'other' && (
        <label>출발 가능 시작
          <select value={windowStart} onChange={(e) => setStart(e.target.value)}>
            {options.map((option) => <option key={option} value={option}>{formatDateTime(option)}</option>)}
          </select>
        </label>
      )}
      <p className="field">얼마나 기다릴 수 있어요?</p>
      <Chips value={length} onChange={setLength} options={[20, 40, 60].map((m) => ({ value: m, label: `${m}분` }))} />
      <p>{formatTime(windowStart)}에서 {formatTime(windowEnd)} 사이에 출발</p>
      <Rows items={[
        ['경로', `${hub?.name || ''}에서 ${place.name}`],
        ['단독 요금', preview.data ? `약 ${won(preview.data.soloFare)}, ${preview.data.soloMinutes}분` : '조회 중'],
        ['같이 타면', '10% 이상 아낄 때만 매칭', true],
      ]} />
      {preview.error && <p className="error">{preview.error}</p>}
      <MemoField value={memo} onChange={setMemo} />
      <p className="muted">{REAL_NAME}</p>
    </Screen>
  )
}

export function SchoolRequest({ initial, config, now, run, onClose }) {
  const [school, setSchool] = useState(null)
  const [place, setPlace] = useState(initialPlace(initial))
  const nine = useMemo(() => nextClock(now, 9, 0), [])
  const tenThirty = useMemo(() => nextClock(now, 10, 30), [])
  const options = useMemo(() => arrivalOptions(now), [])
  const [choice, setChoice] = useState(nine)
  const [other, setOther] = useState(options[0])
  const [memo, setMemo] = useState(initial?.memo || '')

  useEffect(() => {
    api.school().then(setSchool)
  }, [])

  const arriveBy = choice === 'other' ? other : choice
  const body = { direction: 'TO_SCHOOL', arriveBy, memo, ...pointBody(place) }
  const preview = usePreview(place.point ? body : null)

  return (
    <Screen actions={<button disabled={!preview.data} onClick={() => run(async () => { await api.createRequest(body); onClose() })}>일행 찾기</button>}>
      <BackBar onBack={onClose} step="등교" />
      <h2>어디서 타고, 언제 도착할까요?</h2>
      <PlaceField title="승차 지점" center={school} value={place} config={config} onChange={setPlace}
        mapLabel="승차 지점 지도" placeholder="큰길이나 정류장 이름"
        hint="집 주소 대신 택시가 서기 쉬운 큰길이나 정류장을 고르세요." />
      <p className="field">학교 도착 희망 시각</p>
      <Chips value={choice} onChange={setChoice} options={[
        { value: nine, label: formatTime(nine) },
        { value: tenThirty, label: formatTime(tenThirty) },
        { value: 'other', label: '다른 시각' },
      ]} />
      {choice === 'other' && (
        <label>도착 희망 시각
          <select value={other} onChange={(e) => setOther(e.target.value)}>
            {options.map((option) => <option key={option} value={option}>{formatDateTime(option)}</option>)}
          </select>
        </label>
      )}
      <p className="muted">{formatDateTime(arriveBy)}까지 도착</p>
      {place.point && (
        <Rows items={[
          ['단독 요금', preview.data ? `약 ${won(preview.data.soloFare)}, ${preview.data.soloMinutes}분` : '조회 중'],
          ['결과 안내', preview.data ? `늦어도 ${formatTime(preview.data.resultBy)}까지` : '조회 중', true],
        ]} />
      )}
      {preview.error && <p className="error">{preview.error}</p>}
      <p className="muted">학교에서 먼 사람부터 차례로 타고 함께 내립니다. 일행이 안 모이면 다른 교통수단을 탈 시간이 남도록 미리 알려 드려요.</p>
      <MemoField value={memo} onChange={setMemo} />
      <p className="muted">{REAL_NAME}</p>
    </Screen>
  )
}
