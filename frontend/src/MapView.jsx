import React, { useEffect, useRef, useState } from 'react'
import { loadKakaoMaps } from './MapPicker.jsx'

const HAS_KEY = Boolean(import.meta.env.VITE_KAKAO_JS_KEY)

const LINE_WEIGHT = 6
const CASING = 4

/** 지점과 경로만 보여 주는 지도. 패널 크기가 바뀌면 전체가 보이도록 다시 맞춘다. right는 지도를 가리는 카드의 폭이다. */
export default function MapView({ points = [], lines = [], edge = 48, right = 0, onFail, fallback = null }) {
  const box = useRef(null)
  const [failed, setFailed] = useState(!HAS_KEY)
  const key = JSON.stringify([points, lines.map((line) => [line.color, line.path.length, line.path[0]]), right])

  useEffect(() => {
    if (!HAS_KEY) {
      onFail?.()
      return undefined
    }
    let closed = false
    let observer = null
    const drawn = []
    loadKakaoMaps()
      .then((maps) => {
        const first = points[0] || lines[0]?.path[0]
        if (closed || !first) return
        box.current.innerHTML = ''
        const map = new maps.Map(box.current, { center: new maps.LatLng(first.lat, first.lon), level: 7 })
        const bounds = new maps.LatLngBounds()
        const paths = lines.map((line) => line.path.map((p) => new maps.LatLng(p.lat, p.lon)))
        paths.flat().forEach((p) => bounds.extend(p))
        // 흰 테두리를 먼저 깔아 지도의 도로색 위에서도 선이 분리되어 보이게 한다.
        paths.forEach((path) => drawn.push(new maps.Polyline({
          map, path, strokeWeight: LINE_WEIGHT + CASING, strokeColor: '#ffffff', strokeOpacity: 1,
        })))
        lines.forEach((line, i) => drawn.push(new maps.Polyline({
          map, path: paths[i], strokeWeight: LINE_WEIGHT, strokeColor: line.color, strokeStyle: line.style, strokeOpacity: 1,
        })))
        for (const point of points) {
          const position = new maps.LatLng(point.lat, point.lon)
          bounds.extend(position)
          drawn.push(new maps.CustomOverlay({ map, position, content: `<span class="mk ${point.tone || ''}">${point.label}</span>` }))
        }
        const fit = () => {
          map.relayout()
          map.setBounds(bounds, edge, edge + right, edge, edge)
        }
        fit()
        observer = new ResizeObserver(fit)
        observer.observe(box.current)
      })
      .catch(() => {
        if (closed) return
        setFailed(true)
        onFail?.()
      })
    return () => {
      closed = true
      observer?.disconnect()
      drawn.forEach((item) => item.setMap(null))
    }
  }, [key])

  if (failed) return fallback
  return <div ref={box} className={lines.length ? 'map-canvas muted' : 'map-canvas'} role="img" aria-label="지도" />
}
