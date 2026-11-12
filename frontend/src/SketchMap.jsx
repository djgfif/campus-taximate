import React from 'react'

const W = 320
const H = 180
const PAD = 22

/**
 * 지도 키가 없거나 지도를 못 불러올 때 쓰는 약도. 좌표를 화면 비율에 맞게 펴서 경로선과 지점만 그린다.
 * 실제 지도처럼 길 이름은 없지만, 어디서 모여 어느 쪽으로 가는지는 한눈에 보인다.
 */
export default function SketchMap({ points = [], lines = [], label = '약도' }) {
  const all = [...points, ...lines.flatMap((line) => line.path)].filter((p) => Number.isFinite(p?.lat) && Number.isFinite(p?.lon))
  if (all.length === 0) return null
  const lats = all.map((p) => p.lat)
  const lons = all.map((p) => p.lon)
  const minLat = Math.min(...lats), maxLat = Math.max(...lats)
  const minLon = Math.min(...lons), maxLon = Math.max(...lons)
  // 위도 1도와 경도 1도의 실제 길이가 달라 경도에 cos(위도)를 곱해 모양을 맞춘다.
  const k = Math.cos(((minLat + maxLat) / 2) * Math.PI / 180)
  const spanX = Math.max((maxLon - minLon) * k, 1e-4)
  const spanY = Math.max(maxLat - minLat, 1e-4)
  const scale = Math.min((W - PAD * 2) / spanX, (H - PAD * 2) / spanY)
  const ox = (W - spanX * scale) / 2
  const oy = (H - spanY * scale) / 2
  const at = (p) => [ox + (p.lon - minLon) * k * scale, H - (oy + (p.lat - minLat) * scale)]
  return (
    <svg className="sketch-map" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={label}>
      <defs>
        <pattern id="sketch-grid" width="20" height="20" patternUnits="userSpaceOnUse">
          <path d="M 20 0 L 0 0 0 20" fill="none" stroke="currentColor" strokeOpacity="0.06" />
        </pattern>
      </defs>
      <rect width={W} height={H} fill="url(#sketch-grid)" />
      {lines.map((line, i) => (
        <polyline key={i} points={line.path.map((p) => at(p).join(',')).join(' ')} fill="none"
          stroke={line.color} strokeWidth={line.style === 'solid' ? 4 : 2.5} strokeDasharray={line.style === 'solid' ? undefined : '6 6'}
          strokeLinecap="round" strokeLinejoin="round" />
      ))}
      {points.map((p, i) => {
        const [x, y] = at(p)
        return <g key={i} transform={`translate(${x},${y})`}>
          <circle r="11" className={`sketch-pin ${p.tone || ''}`} />
          <text textAnchor="middle" dy="4" className="sketch-label">{p.label}</text>
        </g>
      })}
    </svg>
  )
}
