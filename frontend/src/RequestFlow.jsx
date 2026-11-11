import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import MapPicker, { searchPlaces } from './MapPicker.jsx'
import MapView from './MapView.jsx'
import { formatDateTime, formatTime, requestTimeOptions, won } from './format.js'
import { BackBar, Btn, Chips, Rows, Say, Screen } from './ui.jsx'

const emptyPlace = () => ({ name: '', point: null })

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

function PlaceField({ label, value, center, onChange, samples = [] }) {
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
    <label className="field">{label}<input value={value.name} maxLength={100} placeholder="건물이나 정류장 이름"
      onChange={(e) => onChange({ name: e.target.value, point: null })} /></label>
    {value.point && <p className="note">지도 위치를 선택했어요.</p>}
    {results.length > 0 && <ul className="places">{results.map((place) => <li key={place.id}><button type="button" onClick={() => choose(place)}>{place.name}</button></li>)}</ul>}
    <Btn className="small line" disabled={!center} onClick={() => setOpen(true)}>{label} 지도에서 고르기</Btn>
    {samples.length > 0 && <label className="field">시연 {label}<select value="" onChange={(e) => choose(samples[Number(e.target.value)])}>
      <option value="" disabled>시연용 지점 고르기</option>{samples.map((p, i) => <option key={p.id || p.name} value={i}>{p.name}</option>)}
    </select></label>}
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
  const selected = selection.key === originKey ? selection.ids.filter((id) => nearby.some((p) => p.id === id)) : []
  const changeOrigin = (place) => { setOrigin(place); setSelection({ key: '', ids: [] }) }
  const toggle = (id) => setSelection({ key: originKey, ids: selected.includes(id) ? selected.filter((p) => p !== id) : [...selected, id].slice(0, 3) })
  const options = requestTimeOptions(now)
  const chosenStart = options.includes(start) ? start : options[0]
  const arrivals = requestTimeOptions(now, 30)
  const chosenArrival = arrivals.includes(arrival) ? arrival : arrivals[Math.min(30, arrivals.length - 1)]
  const ready = origin.point && origin.name.trim() && destination.point && destination.name.trim() && selected.length > 0 && chosenStart && chosenArrival
  const body = ready ? {
    direction: 'FROM_HUB', mode, timeBasis: basis, origin: { ...origin.point, name: origin.name }, pointIds: selected,
    pointName: destination.name, pointLat: destination.point.lat, pointLon: destination.point.lon, memo,
    ...(basis === 'DEPART' ? { windowStart: chosenStart, windowEnd: new Date(new Date(chosenStart).getTime() + endLength * 60000).toISOString() }
      : { arriveBy: chosenArrival, earlyArrivalMinutes: early }),
  } : null
  const preview = usePreview(body)
  const submit = async () => {
    if (!body || !preview.data) return
    setError('')
    try { await api.createRequest(body); await run(async () => {}, '요청했어요'); onClose() }
    catch (e) { setError(e.message) }
  }
  const center = hubs[0] || school
  return <Screen aside={origin.point ? <MapView points={[{ ...origin.point, label: '출', tone: 'me' },
    ...(destination.point ? [{ ...destination.point, label: '도', tone: 'end' }] : [])]} /> : undefined}
    foot={<Btn disabled={!preview.data || !ready} onClick={submit}>일행 찾기</Btn>}>
    <BackBar onBack={onClose} title="탑승 요청" />
    <div className="pad">
      <h1 className="head">어디서 출발하나요?</h1>
      <Chips label="요청 방식" value={mode} onChange={setMode} options={[{ value: 'RESERVATION', label: '예약' }, { value: 'INSTANT', label: '즉시' }]} />
      {mode === 'INSTANT' && <Say title="지금부터 25분 뒤 출발 가능">일행을 찾으면 출발 시각을 알려 드려요. 도착 기준도 단독 이동 시간을 뺀 출발 가능 시각으로 확인해요.</Say>}
      <PlaceField label="출발지" value={origin} center={center} onChange={changeOrigin} samples={config?.demo ? hubs : []} />
      <fieldset className="boarding-points"><legend>승차 지점 (1~3곳)</legend>
        {!origin.point ? <p className="note">출발지를 먼저 골라 주세요.</p> : points.key !== originKey ? <p role="status">근처 지점을 찾고 있어요.</p>
          : points.error ? <p role="alert">{points.error}</p> : nearby.length === 0 ? <Say>근처에 승차 지점이 없어요</Say>
            : nearby.map((p) => <label key={p.id} className="point-option" aria-label={p.name + ' 걸어서 약 ' + Math.ceil(p.walkSeconds / 60) + '분' + (p.walkEstimated ? ', 추정' : '')}><input type="checkbox" checked={selected.includes(p.id)} onChange={() => toggle(p.id)} />
              <span><b>{p.name}</b><small>걸어서 약 {Math.ceil(p.walkSeconds / 60)}분{p.walkEstimated ? ', 추정' : ''}</small></span></label>)}
      </fieldset>
      <PlaceField label="도착지" value={destination} center={origin.point || center} onChange={setDestination} samples={config?.demo ? config.sampleDropoffs || [] : []} />
      <Chips label="시간 기준" value={basis} onChange={setBasis} options={[{ value: 'DEPART', label: '출발 기준' }, { value: 'ARRIVE', label: '도착 기준' }]} />
      {basis === 'DEPART' ? <>
        <label className="field">출발 가능 시작<select value={chosenStart || ''} onChange={(e) => setStart(e.target.value)}>{options.map((t) => <option key={t} value={t}>{formatDateTime(t)}</option>)}</select></label>
        <label className="field">출발 가능 범위<select value={endLength} onChange={(e) => setEndLength(Number(e.target.value))}>{Array.from({ length: 13 }, (_, i) => i * 5).map((m) => <option key={m} value={m}>{m}분</option>)}</select></label>
        {chosenStart && <p className="sub">{formatTime(chosenStart)} ~ {formatTime(new Date(chosenStart).getTime() + endLength * 60000)}</p>}
      </> : <>
        <label className="field">늦어도 도착 시각<select value={chosenArrival || ''} onChange={(e) => setArrival(e.target.value)}>{arrivals.map((t) => <option key={t} value={t}>{formatDateTime(t)}</option>)}</select></label>
        <Chips label="일찍 도착 허용" value={early} onChange={setEarly} options={[0, 5, 10, 15].map((m) => ({ value: m, label: m + '분' }))} />
      </>}
      <p className="note">예약은 다음 날 23시 59분까지, 시간은 5분 단위로 골라 주세요.</p>
      <Rows items={[
        ['혼자 타면', preview.data ? '약 ' + won(preview.data.soloFare) + ', ' + preview.data.soloMinutes + '분' : '입력을 마치면 확인해요'],
        ['모집 마감', preview.data ? formatDateTime(preview.data.resultBy) : '미리보기 대기'],
      ]} />
      {(preview.error || error) && <p role="alert">{preview.error || error}</p>}
      <label className="field">일행이 알아볼 메모 (선택)<input maxLength={100} value={memo} onChange={(e) => setMemo(e.target.value)} /></label>
      <p className="note">승차 지점 한 곳에 모여 타고 차례로 내려요. 택시 배차가 확정된 것은 아니에요.</p>
    </div>
  </Screen>
}

export const HomeRequest = (props) => <RequestForm {...props} />
export const SchoolRequest = (props) => <RequestForm {...props} schoolDestination />
