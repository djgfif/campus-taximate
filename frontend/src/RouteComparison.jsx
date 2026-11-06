import React, { useEffect, useRef, useState } from 'react'
import { api } from './api.js'
import { loadKakaoMaps } from './MapPicker.jsx'
import { formatDateTime, won } from './format.js'

export default function RouteComparison({ party, mine }) {
  const container = useRef(null)
  const [unavailable, setUnavailable] = useState(false)
  const itinerary = party.members.map((m) => m.requestId).join(',')

  useEffect(() => {
    let closed = false
    const lines = []
    setUnavailable(false)
    async function draw() {
      try {
        const routes = await api.partyRoutes(party.id)
        if (!routes.shared?.length || !routes.solo?.length || !import.meta.env.VITE_KAKAO_JS_KEY) {
          throw new Error('경로 없음')
        }
        const maps = await loadKakaoMaps()
        if (closed) return
        const bounds = new maps.LatLngBounds()
        const map = new maps.Map(container.current, {
          center: new maps.LatLng(routes.shared[0].lat, routes.shared[0].lon), level: 9,
        })
        for (const [coordinates, strokeStyle, strokeColor] of [
          [routes.shared, 'solid', '#4a5166'], [routes.solo, 'dash', '#d18a00'],
        ]) {
          const path = coordinates.map((p) => new maps.LatLng(p.lat, p.lon))
          path.forEach((p) => bounds.extend(p))
          lines.push(new maps.Polyline({ map, path, strokeWeight: 5, strokeColor, strokeStyle }))
        }
        map.setBounds(bounds)
      } catch {
        if (!closed) setUnavailable(true)
      }
    }
    draw()
    return () => {
      closed = true
      lines.forEach((line) => line.setMap(null))
      if (container.current) container.current.innerHTML = ''
    }
  }, [party.id, itinerary])

  const arrival = new Date(new Date(party.departureAt).getTime() + mine.sharedSeconds * 1000).toISOString()
  return (
    <div>
      <h3>내 경로 비교</h3>
      <p>단독: {Math.ceil(mine.soloSeconds / 60)}분 · {won(mine.soloFare)}</p>
      <p>동승: 내 하차까지 {Math.ceil(mine.sharedSeconds / 60)}분 · 도착 {formatDateTime(arrival)} · 내 예상 분담금 {won(mine.expectedShare)}</p>
      <p className="muted">실선: 동승 경로 · 점선: 단독 경로</p>
      {unavailable && <p className="muted">지도를 불러오지 못했습니다. 시간과 금액을 확인해 주세요.</p>}
      <div ref={container} className="map" hidden={unavailable} role="application" aria-label="동승 경로와 단독 경로 비교" />
    </div>
  )
}
