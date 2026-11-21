import React, { useEffect, useId, useState } from 'react'
import { api } from './api.js'
import MapPicker, { searchPlaces } from './MapPicker.jsx'
import MapView from './MapView.jsx'
import RequestTimePicker, { seoulHourKey } from './RequestTimePicker.jsx'
import { Check, MapPin, X } from 'lucide-react'
import { formatTime, requestTimeOptions, shareCap, won } from './format.js'
import { BackBar, Btn, Chips, Fold, Say, Screen, useWide } from './ui.jsx'
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

export function PlaceField({ label, value, center, onChange, samples = [], placeholder = '건물이나 정류장 이름' }) {
  const inputId = useId()
  const [open, setOpen] = useState(false)
  const [results, setResults] = useState([])
  const centerLat = center?.lat
  const centerLon = center?.lon
  // 결과가 없거나 검색을 쓸 수 없을 때 아무 반응 없이 끝나지 않게 이유를 알려 준다.
  const [note, setNote] = useState('')
  useEffect(() => {
    if (value.point || !value.name.trim()) { setResults([]); setNote(''); return undefined }
    let closed = false
    const timer = setTimeout(() => searchPlaces(value.name, centerLat == null || centerLon == null ? null : { lat: centerLat, lon: centerLon })
      .then((next) => { if (closed) return; setResults(next); setNote(next.length ? '' : '검색 결과가 없어요. 다른 이름으로 찾거나 지도에서 골라 주세요.') })
      .catch(() => { if (closed) return; setResults([]); setNote('지금은 장소 검색을 쓸 수 없어요. 지도에서 골라 주세요.') }), 300)
    return () => { closed = true; clearTimeout(timer) }
  }, [value.name, value.point, centerLat, centerLon])
  const choose = (place) => {
    onChange({ name: place.name, point: { lat: place.lat, lon: place.lon } }); setOpen(false); setResults([]); setNote('')
  }
  const same = (p) => value.point && p.name === value.name && Math.abs(p.lat - value.point.lat) < 1e-6 && Math.abs(p.lon - value.point.lon) < 1e-6
  const picked = samples.findIndex(same)
  return <section className="place-field" aria-label={label}>
    <div className="field">
      <label htmlFor={inputId} className="field-label">{label}</label>
      <span className="place-input">
        <input id={inputId} value={value.name} maxLength={100} placeholder={placeholder}
          onChange={(e) => onChange({ name: e.target.value, point: null })} />
        <span className="place-end">
          {value.point && <Check className="place-ok" size={16} aria-label="위치 선택됨" />}
          {value.name && <button type="button" className="place-clear" aria-label={`${label} 지우기`} onClick={() => { onChange(emptyPlace()); setResults([]); setNote('') }}><X size={15} aria-hidden="true" /></button>}
        </span>
      </span>
    </div>
    {results.length > 0 && <ul className="places">{results.map((place) => <li key={place.id}><button type="button" onClick={() => choose(place)}>{place.name}</button></li>)}</ul>}
    {note && <p className="note" role="status">{note}</p>}
    <div className="place-actions">
      <Btn className="small line" disabled={!center} onClick={() => setOpen(true)}><MapPin size={16} aria-hidden="true" />지도에서<span className="sr-only"> {label} 고르기</span></Btn>
      {/* 고른 시연 지점이 선택 칸에도 보이게 지금 값과 같은 지점을 선택 상태로 둔다. 직접 검색하거나 지도에서 고르면 비운다. */}
      {samples.length > 0 && <label className="field sample"><span className="sr-only">시연 {label}</span><select aria-label={'시연 ' + label}
        value={picked < 0 ? '' : String(picked)}
        onChange={(e) => choose(samples[Number(e.target.value)])}>
        <option value="" disabled>시연 지점</option>{samples.map((p, i) => <option key={p.id || p.name} value={i}>{p.name}</option>)}
      </select></label>}
    </div>
    {open && <MapPicker center={center} value={value.point} title={label + ' 고르기'} onPick={choose} onClose={() => setOpen(false)} />}
  </section>
}

export function RequestForm({ initial, config, now, run, onClose, schoolDestination = false, schoolOrigin = false }) {
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
  const [windowCustom, setWindowCustom] = useState(false)
  const [arrival, setArrival] = useState('')
  const [memo, setMemo] = useState(initial?.memo || '')
  const [points, setPoints] = useState({ key: '', data: [] })
  const [selection, setSelection] = useState({ key: '', ids: [] })
  const [error, setError] = useState('')
  const [waiting, setWaiting] = useState({})
  const direction = schoolDestination ? 'TO_SCHOOL' : 'FROM_HUB'
  // 시간대별로 같은 방향에서 이미 기다리는 사람 수(게시판과 같은 집계). 못 읽으면 표시만 생략한다.
  useEffect(() => {
    let closed = false
    api.board().then((board) => {
      if (closed) return
      const counts = {}
      for (const w of board?.waiting || []) if (w.direction === direction) counts[seoulHourKey(w.hour)] = (counts[seoulHourKey(w.hour)] || 0) + w.count
      setWaiting(counts)
    }).catch(() => {})
    return () => { closed = true }
  }, [direction])
  useEffect(() => {
    let closed = false
    api.hubs().then((data) => !closed && setHubs(data)).catch(() => !closed && setError('승차 지점 정보를 읽지 못했어요. 다시 열어 주세요.'))
    api.school().then((data) => {
      if (closed) return
      setSchool(data)
      if (schoolDestination && (!initial || initial.direction === 'TO_SCHOOL')) setDestination({ name: data.name, point: { lat: data.lat, lon: data.lon } })
      // 하교는 학교에서 출발하므로 출발지를 학교로 채워 둔다. 이미 고른 출발지는 덮어쓰지 않는다.
      if (schoolOrigin) setOrigin((prev) => (prev.point || prev.name.trim() ? prev : { name: data.name, point: { lat: data.lat, lon: data.lon } }))
    }).catch(() => {})
    return () => { closed = true }
  }, [schoolDestination, schoolOrigin, initial])
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
  const windowPresets = [15, 30, 45, 60].filter((m) => m <= maxWindow)
  const customWindow = windowCustom || !windowPresets.includes(chosenLength)
  // 모의실험(C9): 같은 출발 시각이라도 15분 일찍 요청하면 매칭률이 올랐다. 최소 준비 25분 + 15분 = 40분 미만이면 알려 준다.
  const leadMinutes = chosenStart ? Math.round((new Date(chosenStart) - new Date(now)) / 60000) : null
  const shortLead = !instant && basis === 'DEPART' && leadMinutes !== null && leadMinutes < 40
  // 모의실험(C13, 수도권 79곳): 출발 가능 범위를 넓힐수록 모든 학교에서 매칭률이 올랐다(하루 35건 중앙값 10분 26%, 20분 48%, 30분 60%).
  // 더 넓힐 수 있는데 30분보다 좁게 고르면 알려 준다.
  const narrowWindow = !instant && basis === 'DEPART' && chosenStart && chosenLength < 30 && maxWindow >= 30
  const minSaving = config?.rules?.minSavingRate ?? 0.1
  const maxDelay = config?.rules?.maxDelayMinutes ?? 15
  const waitingLabel = schoolDestination ? '등교하는 학생' : '하교하는 학생'
  const ready = origin.point && origin.name.trim() && destination.point && destination.name.trim() && selected.length > 0 && (instant || chosenStart && chosenArrival)
  const body = ready ? {
    direction: 'FROM_HUB', mode, timeBasis: effectiveBasis, origin: { ...origin.point, name: origin.name }, pointIds: selected,
    pointName: destination.name, pointLat: destination.point.lat, pointLon: destination.point.lon, memo,
    ...(instant ? {} : basis === 'DEPART' ? { windowStart: chosenStart, windowEnd: new Date(new Date(chosenStart).getTime() + chosenLength * 60000).toISOString() }
      : { arriveBy: chosenArrival, earlyArrivalMinutes: early }),
  } : null
  const preview = usePreview(body)
  const wide = useWide()
  const tripPoints = [
    origin.point && { ...origin.point, label: '출', name: origin.name, tone: 'me' },
    destination.point && { ...destination.point, label: '도', name: destination.name, tone: 'end' },
  ].filter(Boolean)
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
      {/* 요청 전에 정확한 몫은 알 수 없다(누구와 묶일지 모름). 대신 매칭 규칙이 보장하는 상한을 보여 준다. */}
      <p className="foot-sum" aria-live="polite">{preview.data
        ? <>혼자 <s>{won(preview.data.soloFare)}</s> → 같이 타면 <b>{won(shareCap(preview.data.soloFare, minSaving))} 이하</b></>
        : ready ? '요금 계산 중…' : '어디서 어디로 가는지 먼저 골라 주세요'}</p>
      <p className="foot-rule">{preview.data
        ? `${Math.round(minSaving * 100)}% 이상 싸고 ${maxDelay}분 넘게 늦어지지 않을 때만 일행이 돼요. 결과는 ${formatTime(preview.data.resultBy)}까지 알려 드려요.`
        : `늦어져도 ${maxDelay}분 이내, 혼자보다 ${Math.round(minSaving * 100)}% 이상 쌀 때만 일행이 돼요.`}</p>
      <Btn disabled={!preview.data || !ready} onClick={submit}>일행 찾기</Btn>
    </>}>
    <BackBar onBack={onClose} title={schoolDestination ? '등교' : '하교'} />
    <div className="pad request-form">
      <Chips label="요청 방식" value={mode} onChange={setMode} options={[{ value: 'RESERVATION', label: '예약' }, { value: 'INSTANT', label: '즉시' }]} />
      {instant && <p className="note mode-note">{matchMinutes}분 동안 같이 탈 사람을 찾아요. 걸어서 {Math.floor(maxWalk / 60)}분 안 지점만 고를 수 있어요.</p>}

      <section className="form-step" aria-labelledby="step-where">
        <h2 id="step-where" className="sr-only">어디서 어디로</h2>
        <div className="route-box">
          <PlaceField label="출발지" placeholder="출발지 검색" value={origin} center={center} onChange={changeOrigin} samples={config?.demo ? hubs : []} />
          <PlaceField label="도착지" placeholder="도착지 검색" value={destination} center={origin.point || center} onChange={setDestination} samples={config?.demo ? config.sampleDropoffs || [] : []} />
        </div>
        {!wide && origin.point && <div className="mini-map">
          <MapView points={tripPoints} edge={28} fallback={<SketchMap points={tripPoints} lines={tripLine} label="출발지와 도착지 약도" />} />
        </div>}
      </section>

      <section className="form-step" aria-labelledby="step-meet">
        <h2 id="step-meet">어디서 모일까요<small>최대 3곳</small></h2>
        <fieldset className="boarding-points"><legend className="sr-only">승차 지점 (1~3곳)</legend>
          {!origin.point ? <p className="note">출발지를 먼저 골라 주세요.</p> : points.key !== originKey ? <p role="status">근처 지점을 찾고 있어요.</p>
            : points.error ? <p role="alert">{points.error}</p> : nearby.length === 0 ? <Say>근처에 승차 지점이 없어요</Say>
              : nearby.map((p) => <label key={p.id} className="point-option" aria-label={p.name + ' 걸어서 약 ' + Math.ceil(p.walkSeconds / 60) + '분' + (p.walkEstimated ? ', 추정' : '')}><input type="checkbox" checked={selected.includes(p.id)} disabled={instant&&p.walkSeconds>maxWalk} onChange={() => toggle(p.id)} />
                <span><b>{p.name}</b><small>걸어서 약 {Math.ceil(p.walkSeconds / 60)}분{p.walkEstimated ? ', 추정' : ''}</small>{instant&&p.walkSeconds>maxWalk&&<small>즉시 선택 불가</small>}</span></label>)}
        </fieldset>
      </section>

      {!instant && <section className="form-step when-card" aria-labelledby="step-when">
        <div className="when-head">
          <h2 id="step-when">언제</h2>
          {timeSummary && <span className="when-summary">{timeSummary}</span>}
        </div>
        <Chips label="시간 기준" value={basis} onChange={setBasis} options={[{ value: 'DEPART', label: '출발 기준' }, { value: 'ARRIVE', label: '도착 기준' }]} />
        {basis === 'DEPART' ? <>
          <RequestTimePicker label="출발 가능 시작" options={options} value={chosenStart} now={now} onChange={setStart} waiting={waiting} waitingLabel={waitingLabel} />
          {/* 범위는 칩 한 줄로 고른다. 0~60분 5분 단위 선택은 '직접'을 누르거나 칩에 없는 값일 때만 연다. */}
          <div className="window-picker">
            <span className="window-label" id="window-label">출발 가능 범위</span>
            <div className="window-presets" role="group" aria-labelledby="window-label">
              {windowPresets.map((m) => (
                <button key={m} type="button" className={!customWindow && chosenLength === m ? 'chip on' : 'chip'} aria-pressed={!customWindow && chosenLength === m}
                  onClick={() => { setWindowCustom(false); setEndLength(m) }}>{m}분</button>
              ))}
              <button type="button" className={customWindow ? 'chip on' : 'chip'} aria-pressed={customWindow} onClick={() => setWindowCustom(true)}>직접</button>
            </div>
            {customWindow && <label className="field window-select"><span className="sr-only">출발 가능 범위 직접 고르기</span><select aria-label="출발 가능 범위 직접 고르기" value={chosenLength} onChange={(e) => setEndLength(Number(e.target.value))}>{Array.from({ length: 13 }, (_, i) => i * 5).filter((m) => m <= maxWindow).map((m) => <option key={m} value={m}>{m}분</option>)}</select></label>}
          </div>
          {chosenStart && <p className="range">{formatTime(chosenStart)} ~ {formatTime(new Date(chosenStart).getTime() + chosenLength * 60000)} 사이에 출발</p>}
        </> : <>
          <RequestTimePicker label="늦어도 도착 시각" options={arrivals} value={chosenArrival} now={now} onChange={setArrival} waiting={waiting} waitingLabel={waitingLabel} />
          <Chips label="일찍 도착 허용" value={early} onChange={setEarly} options={[0, 5, 10, 15].map((m) => ({ value: m, label: m + '분' }))} />
        </>}
      </section>}
      {/* 같은 이유(같이 탈 사람이 적다)의 안내는 한 문장으로 묶는다. */}
      {(shortLead || narrowWindow) && <p className="note lead-note">{shortLead && narrowWindow
        ? `출발까지 ${leadMinutes}분, 출발 가능 범위 ${chosenLength}분이라 같이 탈 학생을 찾기 어려워요. 40분 넘게 여유를 두고 범위를 30분 넘게 잡으면 일행이 생기기 쉬워요.`
        : shortLead
          ? `출발까지 ${leadMinutes}분이라 같이 탈 사람을 찾을 시간이 짧아요. 40분 넘게 여유를 두고 올리면 일행이 생기기 쉬워요.`
          : `출발 가능 범위가 ${chosenLength}분이라 같이 탈 학생을 찾기 어려워요. 30분 넘게 두면 일행이 생기기 쉬워요.`}</p>}

      <Fold title="일행에게 남길 메모 (선택)" className="memo-fold" defaultOpen={Boolean(memo)}>
        <label className="field"><span className="sr-only">일행이 알아볼 메모 (선택)</span><input maxLength={100} value={memo} placeholder="예: 검은 백팩" onChange={(e) => setMemo(e.target.value)} /></label>
      </Fold>
      {(preview.error || error) && <p role="alert">{preview.error || error}</p>}
    </div>
  </Screen>
}

export const HomeRequest = (props) => <RequestForm {...props} schoolOrigin />
export const SchoolRequest = (props) => <RequestForm {...props} schoolDestination />
