import React, { useEffect, useMemo, useState } from 'react'
import { MapPin, X } from 'lucide-react'
import { api } from './api.js'
import { formatDateTime, formatTime, won } from './format.js'
import MapPicker, { searchPlaces } from './MapPicker.jsx'
import MapView from './MapView.jsx'
import { BackBar, Btn, Chips, Rail, Rows, Say, Screen, useWide } from './ui.jsx'

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

function PlaceField({ title, hint, center, value, config, onChange, mapTitle, placeholder }) {
  const [results, setResults] = useState([])
  const [mapOpen, setMapOpen] = useState(false)
  const samples = config?.sampleDropoffs || []
  const wide = useWide()

  useEffect(() => {
    if (value.point) {
      setResults([])
      return undefined
    }
    let closed = false
    const timer = setTimeout(() => {
      searchPlaces(value.name, center).then((list) => !closed && setResults(list)).catch(() => setResults([]))
    }, 300)
    return () => {
      closed = true
      clearTimeout(timer)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps -- 시각 선택지와 검색은 정해진 값이 바뀔 때만 다시 계산한다
  }, [value.name, value.point])

  const choose = (place) => {
    setResults([])
    setMapOpen(false)
    onChange({ name: place.name, point: { lat: place.lat, lon: place.lon } })
  }

  if (value.point) {
    return (
      <div className="field">{title}
        <div className="chosen">
          <div><b>{value.name}</b><small>일행에게는 이 이름만 보입니다</small></div>
          <button type="button" className="icon-btn" aria-label="다시 고르기" onClick={() => onChange({ name: '', point: null })}><X size={20} /></button>
        </div>
      </div>
    )
  }

  return (
    <>
      <label className="field">{title}
        <input maxLength={100} value={value.name} placeholder={placeholder} autoComplete="off"
          onChange={(e) => onChange({ name: e.target.value, point: null })} />
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
      {!wide && <button type="button" className="map-open" onClick={() => setMapOpen(true)}><MapPin size={18} aria-hidden="true" />지도에서 고르기</button>}
      {config?.demo && (
        <label className="field">시연 지점
          <select value="" onChange={(e) => choose({ ...samples[Number(e.target.value)] })}>
            <option value="" disabled>시연용 지점 고르기</option>
            {samples.map((item, index) => <option key={item.name} value={index}>{item.name}</option>)}
          </select>
        </label>
      )}
      <p className="note">{hint}</p>
      {mapOpen && <MapPicker center={center} title={mapTitle} onPick={choose} onClose={() => setMapOpen(false)} />}
    </>
  )
}

function MemoField({ value, onChange }) {
  return (
    <label className="field">일행이 알아볼 메모 (선택)
      <input maxLength={100} value={value} onChange={(e) => onChange(e.target.value)} placeholder="검은 가방" />
    </label>
  )
}

/** 넓은 화면의 오른쪽. 지점을 정하기 전에는 고르는 지도, 정한 뒤에는 확인 지도다. */
function PlaceSide({ place, anchor, anchorLabel, mineLabel, title, onChange }) {
  if (!anchor) return null
  if (!place.point) {
    return <MapPicker embedded center={anchor} title={title} onPick={(p) => onChange({ name: p.name, point: { lat: p.lat, lon: p.lon } })} />
  }
  return (
    <MapView points={[
      { ...place.point, label: mineLabel, tone: 'me' },
      { lat: anchor.lat, lon: anchor.lon, label: anchorLabel, tone: 'end' },
    ]} />
  )
}

const pointBody = (place) => ({ pointName: place.name, pointLat: place.point?.lat, pointLon: place.point?.lon })

function initialPlace(initial) {
  return initial
    ? { name: initial.pointName, point: { lat: initial.pointLat, lon: initial.pointLon } }
    : { name: '', point: null }
}

const fare = (preview) => (preview.data ? `약 ${won(preview.data.soloFare)}, ${preview.data.soloMinutes}분` : '확인 중')

export function HomeRequest({ initial, config, now, run, onClose }) {
  const [step, setStep] = useState(initial ? 2 : 1)
  const [hubs, setHubs] = useState([])
  const [hubId, setHubId] = useState(initial?.hubId || null)
  const [place, setPlace] = useState(initialPlace(initial))
  // eslint-disable-next-line react-hooks/exhaustive-deps -- 시각 선택지와 검색은 정해진 값이 바뀔 때만 다시 계산한다
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

  const side = <PlaceSide place={place} anchor={hub} anchorLabel="출" mineLabel="도" title="하차 지점 고르기" onChange={setPlace} />

  if (step === 1) {
    return (
      <Screen aside={side} foot={<Btn disabled={!place.point || !place.name} onClick={() => setStep(2)}>다음</Btn>}>
        <BackBar onBack={onClose} step={1} total={2} />
        <div className="pad">
          <h1 className="head">어디서 내리세요?</h1>
          {hubs.length > 1 && (
            <label className="field">출발 거점
              <select value={hubId || ''} onChange={(e) => setHubId(Number(e.target.value))}>
                {hubs.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select>
            </label>
          )}
          <PlaceField title="하차 지점" center={hub} value={place} config={config} onChange={setPlace}
            mapTitle="하차 지점 고르기" placeholder="건물이나 정류장 이름"
            hint="집 주소 대신 근처 건물이나 정류장을 골라 주세요." />
        </div>
      </Screen>
    )
  }

  return (
    <Screen aside={side} foot={<Btn disabled={!preview.data} onClick={() => run(async () => { await api.createRequest(body); onClose() }, '요청했어요')}>일행 찾기</Btn>}>
      <BackBar onBack={() => (initial ? onClose() : setStep(1))} step={2} total={2} />
      <div className="pad">
        <h1 className="head">언제 출발할 수 있어요?</h1>
        <Chips label="출발 시작 시각" value={choice} onChange={setChoice} options={[
          { value: 'first', label: formatTime(options[0]) },
          { value: 'later', label: formatTime(options[6]) },
          { value: 'other', label: '다른 시각' },
        ]} />
        {choice === 'other' && (
          <label className="field">출발 가능 시작
            <select value={windowStart} onChange={(e) => setStart(e.target.value)}>
              {options.map((option) => <option key={option} value={option}>{formatDateTime(option)}</option>)}
            </select>
          </label>
        )}
        <div className="field">얼마나 기다릴 수 있어요?
          <Chips label="기다릴 수 있는 시간" value={length} onChange={setLength} options={[20, 40, 60].map((m) => ({ value: m, label: `${m}분` }))} />
        </div>
        <p className="sub">{formatTime(windowStart)}에서 {formatTime(windowEnd)} 사이에 출발해요</p>
      </div>
      <div className="band" />
      <div className="block">
        <Rail items={[
          { key: 'from', pin: '출', name: hub?.name || '' },
          { key: 'to', pin: '도', name: place.name, me: true },
        ]} />
        <Rows items={[
          ['혼자 타면', fare(preview)],
          ['같이 타면', '10% 이상 아낄 때만 매칭', 'save'],
        ]} />
        {preview.error && <Say tone="stop-tone">{preview.error}</Say>}
        <MemoField value={memo} onChange={setMemo} />
        <p className="note">{REAL_NAME}</p>
      </div>
    </Screen>
  )
}

export function SchoolRequest({ initial, config, now, run, onClose }) {
  const [school, setSchool] = useState(null)
  const [place, setPlace] = useState(initialPlace(initial))
  // eslint-disable-next-line react-hooks/exhaustive-deps -- 시각 선택지와 검색은 정해진 값이 바뀔 때만 다시 계산한다
  const nine = useMemo(() => nextClock(now, 9, 0), [])
  // eslint-disable-next-line react-hooks/exhaustive-deps -- 시각 선택지와 검색은 정해진 값이 바뀔 때만 다시 계산한다
  const tenThirty = useMemo(() => nextClock(now, 10, 30), [])
  // eslint-disable-next-line react-hooks/exhaustive-deps -- 시각 선택지와 검색은 정해진 값이 바뀔 때만 다시 계산한다
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
    <Screen aside={<PlaceSide place={place} anchor={school} anchorLabel="학" mineLabel="승" title="승차 지점 고르기" onChange={setPlace} />}
      foot={<Btn disabled={!preview.data} onClick={() => run(async () => { await api.createRequest(body); onClose() }, '요청했어요')}>일행 찾기</Btn>}>
      <BackBar onBack={onClose} />
      <div className="pad">
        <h1 className="head">어디서 타고,<br />언제 도착할까요?</h1>
        <PlaceField title="승차 지점" center={school} value={place} config={config} onChange={setPlace}
          mapTitle="승차 지점 고르기" placeholder="큰길이나 정류장 이름"
          hint="택시가 서기 쉬운 큰길이나 정류장을 골라 주세요." />
        <div className="field">학교 도착 희망 시각
          <Chips label="학교 도착 희망 시각" value={choice} onChange={setChoice} options={[
            { value: nine, label: formatTime(nine) },
            { value: tenThirty, label: formatTime(tenThirty) },
            { value: 'other', label: '다른 시각' },
          ]} />
        </div>
        {choice === 'other' && (
          <label className="field">도착 희망 시각
            <select value={other} onChange={(e) => setOther(e.target.value)}>
              {options.map((option) => <option key={option} value={option}>{formatDateTime(option)}</option>)}
            </select>
          </label>
        )}
      </div>
      <div className="band" />
      <div className="block">
        {place.point && (
          <Rows items={[
            ['혼자 타면', fare(preview)],
            ['결과 안내', preview.data ? `늦어도 ${formatTime(preview.data.resultBy)}까지` : '확인 중', 'save'],
          ]} />
        )}
        {preview.error && <Say tone="stop-tone">{preview.error}</Say>}
        <MemoField value={memo} onChange={setMemo} />
        <p className="note">학교에서 먼 사람부터 차례로 타고 함께 내립니다. {REAL_NAME}</p>
      </div>
    </Screen>
  )
}
