import React, { useId } from 'react'

const W = 360
const H = 196
const PAD = 34

const shortName = (text) => {
  const value = String(text || '').trim()
  return value.length > 10 ? `${value.slice(0, 9)}…` : value
}

/**
 * 지도 키가 없거나 지도를 못 불러올 때 쓰는 약도. 좌표를 화면 비율에 맞게 펴서 경로선과 지점·지명을 그린다.
 * 실제 길 정보가 없으므로 도로처럼 보이는 선은 그리지 않는다. 옅은 격자와 경로선·지명만 그린다.
 */
export default function SketchMap({ points = [], lines = [], label = '약도' }) {
  const gridId = useId()
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
        <pattern id={gridId} width="28" height="28" patternUnits="userSpaceOnUse">
          <path d="M 28 0 L 0 0 0 28" fill="none" stroke="#dde1dc" strokeWidth="1" />
        </pattern>
      </defs>
      <rect width={W} height={H} fill="#eef1ec" />
      <rect width={W} height={H} fill={`url(#${gridId})`} />
      {lines.map((line, i) => {
        const pts = line.path.map((p) => at(p).join(',')).join(' ')
        const solid = line.style === 'solid'
        return <g key={i}>
          {solid && <polyline points={pts} fill="none" stroke="#ffffff" strokeWidth="9" strokeLinecap="round" strokeLinejoin="round" />}
          <polyline points={pts} fill="none" stroke={line.color} strokeWidth={solid ? 5 : 2.5}
            strokeDasharray={solid ? undefined : '6 6'} strokeLinecap="round" strokeLinejoin="round" />
        </g>
      })}
      {points.map((p, i) => {
        const [x, y] = at(p)
        const tag = shortName(p.name)
        const above = y > H * 0.62
        const tagY = above ? -22 : 24
        const tagW = tag ? Math.min(116, tag.length * 9 + 16) : 0
        const clampedDx = tag ? Math.max(tagW / 2 - x + 8, Math.min(W - tagW / 2 - x - 8, 0)) : 0
        return <g key={i} transform={`translate(${x},${y})`}>
          <circle r="12" className={`sketch-pin ${p.tone || ''}`} />
          <text textAnchor="middle" dy="4" className="sketch-label">{p.label}</text>
          {tag && <g transform={`translate(${clampedDx},${tagY})`}>
            <rect x={-tagW / 2} y="-10" width={tagW} height="19" rx="9.5" className="sketch-tag-bg" />
            <text textAnchor="middle" dy="3.5" className="sketch-tag-text">{tag}</text>
          </g>}
        </g>
      })}
    </svg>
  )
}
