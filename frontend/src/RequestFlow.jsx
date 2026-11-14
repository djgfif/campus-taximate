import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import MapPicker, { searchPlaces } from './MapPicker.jsx'
import MapView from './MapView.jsx'
import RequestTimePicker from './RequestTimePicker.jsx'
import { Check, MapPin } from 'lucide-react'
import { formatTime, requestTimeOptions, won } from './format.js'
import { BackBar, Btn, Chips, Say, Screen, useWide } from './ui.jsx'
import SketchMap from './SketchMap.jsx'

const emptyPlace = () => ({ name: '', point: null })
const seoulDay = (value) => new Date(new Date(value).getTime() + 9 * 3600000).toISOString().slice(0, 10)
const dayWord = (value, now) => (seoulDay(value) === seoulDay(now) ? '오늘' : '내일')

/** 입력이 바뀐 뒤 이전 응답이 도착해도 새 입력의 결과로 표시하지 않는다. */
export function usePreview(body) {
  const key = body ? JSON.stringify(body) : ''
  const [result, setResult] = useState({ key: '', data: null })
  useEffect(() => {
    if (!key) return undefined
    let closed = false
    const timer = setTimeout(() => {
      api.previewRequest(JSON.parse(key)).then((data) => !closed && setResult({ key, data }))
        .catch((error) => !closed && setResult({ key, error: error.message }))
    }, 300)
    return () => { closed = true; clearTimeout(timer) }
  }, [key])
  return result.key === key ? result : {}
}

export function PlaceField({ label, value, center, onChange, samples = [] }) {
  const [open, setOpen] = useState(false)
  const [results, setResults] = useState([])
  const centerLat = center?.lat
  const centerLon = center?.lon
  useEffect(() => {
    if (value.point || !value.name.trim()) { setResults([]); return undefined }
    let closed = false
    const timer = setTimeout(() => searchPlaces(value.name, centerLat == null || centerLon == null ? null : { lat: centerLat, lon: centerLon }).then((next) => !closed && setResults(next))
      .catch(() => !closed && setResults([])), 300)
    return () => { closed = true; clearTimeout(timer) }
  }, [value.name, value.point, centerLat, centerLon])
  const choose = (place) => {
    onChange({ name: place.name, point: { lat: place.lat, lon: place.lon } }); setOpen(false); setResults([])
  }
  return <section className="place-field" aria-label={label}>
    <label className="field">{label}<span className="place-input"><input value={value.name} maxLength={100} placeholder="건물이나 정류장 이름"
      onChange={(e) => onChange({ name: e.target.value, point: null })} />{value.point && <Check className="place-ok" size={18} aria-label="위치 선택됨" />}</span></label>
    {results.length > 0 && <ul className="places">{results.map((place) => <li key={place.id}><button type="button" onClick={() => choose(place)}>{place.name}</button></li>)}</ul>}
    <div className="place-actions">
      <Btn className="small line" disabled={!center} onClick={() => setOpen(true)}><MapPin size={16} aria-hidden="true" />지도에서<span className="sr-only"> {label} 고르기</span></Btn>
      {samples.length > 0 && <label className="field sample"><span className="sr-only">시연 {label}</span><select aria-label={'시연 ' + label} value="" onChange={(e) => choose(samples[Number(e.target.value)])}>
        <option value="" disabled>시연 지점</option>{samples.map((p, i) => <option key={p.id || p.name} value={i}>{p.name}</option>)}
      </select></label>}
    </div>
    {open && <MapPicker center={center} value={value.point} title={label + ' 고르기'} onPick={choose} onClose={() => setOpen(false)} />}
  </section>
}

export function RequestForm({ initial, config, now, run, onClose, schoolDestination = false }) {
  const [hubs, setHubs] = useState([])
  const [school, setSchool] = useState(null)
  const [origin, setOrigin] = useState(emptyPlace)
  const [destination, setDestination] = useState(() => initial && initial.direction !== 'TO_SCHOOL'
    ? { name: initial.pointName, point: { lat: initial.pointLat, lon: initial.pointLon } } : emptyPlace())
  const [mode, setMode] = useState('RESERVATION')
  const [basis, setBasis] = useState(schoolDestination ? 'ARRIVE' : 'DEPART')
  const [early, setEarly] = useState(10)
  const [start, setStart] = useState('')
  const [endLength, setEndLength] = useState(60)
  const [arrival, setArrival] = useState('')
  const [memo, setMemo] = useState(initial?.memo || '')
  const [points, setPoints] = useState({ key: '', data: [] })
  const [selection, setSelection] = useState({ key: '', ids: [] })
  const [error, setError] = useState('')
  useEffect(() => {
    let closed = false
    api.hubs().then((data) => !closed && setHubs(data)).catch(() => !closed && setError('승차 지점 정보를 읽지 못했어요. 다시 열어 주세요.'))
    api.school().then((data) => {
      if (closed) return
      setSchool(data)
      if (schoolDestination && (!initial || initial.direction === 'TO_SCHOOL')) setDestination({ name: data.name, point: { lat: data.lat, lon: data.lon } })
    }).catch(() => {})
    return () => { closed = true }
  }, [schoolDestination, initial])
  const originKey = origin.point ? JSON.stringify(origin.point) : ''
  useEffect(() => {
    if (!originKey) return undefined
    let closed = false
    api.nearbyPoints(JSON.parse(originKey)).then((data) => !closed && setPoints({ key: originKey, data }))
      .catch((e) => !closed && setPoints({ key: originKey, data: [], error: e.message }))
    return () => { closed = true }
  }, [originKey])
  const nearby = points.key === originKey ? points.data : []
  const instant=mode==='INSTANT'
  const maxWalk=config?.instantMatching?.maxWalkSeconds ?? 300
  const matchMinutes=config?.instantMatching?.matchMinutes ?? 5
  const effectiveBasis=instant?'DEPART':basis
  const usable = (p) => !instant || p.walkSeconds <= maxWalk
  // 직접 고르기 전에는 가장 가까운 지점 하나를 미리 골라 둔다.
  const selected = selection.key === originKey ? selection.ids.filter((id) => nearby.some((p) => p.id === id && usable(p)))
    : nearby.filter(usable).slice(0, 1).map((p) => p.id)
  const changeOrigin = (place) => { setOrigin(place); setSelection({ key: '', ids: [] }) }
  const toggle = (id) => setSelection({ key: originKey, ids: selected.includes(id) ? selected.filter((p) => p !== id) : [...selected, id].slice(0, 3) })
  const options = requestTimeOptions(now)
  const chosenStart = options.includes(start) ? start : options[0]
  const arrivals = requestTimeOptions(now, 30)
  const chosenArrival = arrivals.includes(arrival) ? arrival : arrivals[Math.min(30, arrivals.length - 1)]
  const maxWindow = chosenStart ? Math.min(60, (new Date(options.at(-1)) - new Date(chosenStart)) / 60000) : 0
  const chosenLength = Math.min(endLength, maxWindow)
  const ready = origin.point && origin.name.trim() && destination.point && destination.name.trim() && selected.length > 0 && (instant || chosenStart && chosenArrival)
  const body = ready ? {
    direction: 'FROM_HUB', mode, timeBasis: effectiveBasis, origin: { ...origin.point, name: origin.name }, pointIds: selected,
    pointName: destination.name, pointLat: destination.point.lat, pointLon: destination.point.lon, memo,
    ...(instant ? {} : basis === 'DEPART' ? { windowStart: chosenStart, windowEnd: new Date(new Date(chosenStart).getTime() + chosenLength * 60000).toISOString() }
      : { arriveBy: chosenArrival, earlyArrivalMinutes: early }),
  } : null
  const preview = usePreview(body)
  const wide = useWide()
  const tripPoints = [origin.point && { ...origin.point, label: '출', tone: 'me' }, destination.point && { ...destination.point, label: '도', tone: 'end' }].filter(Boolean)
  const tripLine = tripPoints.length === 2 ? [{ path: tripPoints, style: 'dash', color: '#8a5a00' }] : []
  const timeSummary = basis === 'DEPART'
    ? (chosenStart ? `${dayWord(chosenStart, now)} ${formatTime(chosenStart)} ~ ${formatTime(new Date(chosenStart).getTime() + chosenLength * 60000)} 출발` : '')
    : (chosenArrival ? `${dayWord(chosenArrival, now)} ${formatTime(chosenArrival)}까지 도착` : '')
  const submit = async () => {
    if (!body || !preview.data) return
    setError('')
    try { await api.createRequest(body); await run(async () => {}, '요청했어요'); onClose() }
    catch (e) { setError(e.message) }
  }
  const center = hubs[0] || school
  return <Screen aside={origin.point ? <MapView points={[{ ...origin.point, label: '출', tone: 'me' },
    ...(destination.point ? [{ ...destination.point, label: '도', tone: 'end' }] : [])]} /> : undefined}
    foot={<>
      <p className="foot-sum" aria-live="polite">{preview.data
        ? <>혼자 타면 <b>{'약 ' + won(preview.data.soloFare) + ', ' + preview.data.soloMinutes + '분'}</b> · 결과 {formatTime(preview.data.resultBy)}까지</>
        : ready ? '요금 계산 중…' : '어디서 어디로 가는지 먼저 골라 주세요'}</p>
      <Btn disabled={!preview.data || !ready} onClick={submit}>일행 찾기</Btn>
    </>}>
    <BackBar onBack={onClose} title="탑승 요청" />
    <div className="pad request-form">
      <Chips label="요청 방식" value={mode} onChange={setMode} options={[{ value: 'RESERVATION', label: '예약' }, { value: 'INSTANT', label: '즉시' }]} />
      {instant && <p className="note mode-note">{matchMinutes}분 동안 같이 탈 사람을 찾아요. 걸어서 {Math.floor(maxWalk / 60)}분 안 지점만 고를 수 있어요.</p>}

      <section className="form-step" aria-labelledby="step-where">
        <h2 id="step-where"><span>1</span>어디서 어디로</h2>
        <PlaceField label="출발지" value={origin} center={center} onChange={changeOrigin} samples={config?.demo ? hubs : []} />
        <PlaceField label="도착지" value={destination} center={origin.point || center} onChange={setDestination} samples={config?.demo ? config.sampleDropoffs || [] : []} />
        {!wide && origin.point && <div className="mini-map">
          <MapView points={tripPoints} edge={28} fallback={<SketchMap points={tripPoints} lines={tripLine} label="출발지와 도착지 약도" />} />
        </div>}
      </section>

      <section className="form-step" aria-labelledby="step-meet">
        <h2 id="step-meet"><span>2</span>어디서 모일까요 <small>최대 3곳</small></h2>
        <fieldset className="boarding-points"><legend className="sr-only">승차 지점 (1~3곳)</legend>
          {!origin.point ? <p className="note">출발지를 먼저 골라 주세요.</p> : points.key !== originKey ? <p role="status">근처 지점을 찾고 있어요.</p>
            : points.error ? <p role="alert">{points.error}</p> : nearby.length === 0 ? <Say>근처에 승차 지점이 없어요</Say>
              : nearby.map((p) => <label key={p.id} className="point-option" aria-label={p.name + ' 걸어서 약 ' + Math.ceil(p.walkSeconds / 60) + '분' + (p.walkEstimated ? ', 추정' : '')}><input type="checkbox" checked={selected.includes(p.id)} disabled={instant&&p.walkSeconds>maxWalk} onChange={() => toggle(p.id)} />
                <span><b>{p.name}</b><small>걸어서 약 {Math.ceil(p.walkSeconds / 60)}분{p.walkEstimated ? ', 추정' : ''}</small>{instant&&p.walkSeconds>maxWalk&&<small>즉시 선택 불가</small>}</span></label>)}
        </fieldset>
      </section>

      {!instant && <details className="form-step when">
        <summary><h2 id="step-when"><span>3</span>언제</h2><b>{timeSummary}</b><em>바꾸기</em></summary>
        <Chips label="시간 기준" value={basis} onChange={setBasis} options={[{ value: 'DEPART', label: '출발 기준' }, { value: 'ARRIVE', label: '도착 기준' }]} />
        {basis === 'DEPART' ? <>
          <RequestTimePicker label="출발 가능 시작" options={options} value={chosenStart} now={now} onChange={setStart} />
          <label className="field">출발 가능 범위<select value={chosenLength} onChange={(e) => setEndLength(Number(e.target.value))}>{Array.from({ length: 13 }, (_, i) => i * 5).filter((m) => m <= maxWindow).map((m) => <option key={m} value={m}>{m}분</option>)}</select></label>
          {chosenStart && <p className="range">{formatTime(chosenStart)} ~ {formatTime(new Date(chosenStart).getTime() + chosenLength * 60000)} 사이에 출발</p>}
        </> : <>
          <RequestTimePicker label="늦어도 도착 시각" options={arrivals} value={chosenArrival} now={now} onChange={setArrival} />
          <Chips label="일찍 도착 허용" value={early} onChange={setEarly} options={[0, 5, 10, 15].map((m) => ({ value: m, label: m + '분' }))} />
        </>}
      </details>}

      <details className="memo">
        <summary>일행에게 남길 메모 (선택)</summary>
        <label className="field"><span className="sr-only">일행이 알아볼 메모 (선택)</span><input maxLength={100} value={memo} placeholder="예: 검은 백팩" onChange={(e) => setMemo(e.target.value)} /></label>
      </details>
      {(preview.error || error) && <p role="alert">{preview.error || error}</p>}
    </div>
  </Screen>
}

export const HomeRequest = (props) => <RequestForm {...props} />
export const SchoolRequest = (props) => <RequestForm {...props} schoolDestination />
