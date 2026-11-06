import React, { useEffect, useRef, useState } from 'react'

const APP_KEY = import.meta.env.VITE_KAKAO_JS_KEY
const INITIAL_LEVEL = 9

let sdk = null

/** 카카오맵 SDK를 한 번만 불러온다. */
function loadKakaoMaps() {
  if (!sdk) {
    sdk = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${APP_KEY}&autoload=false`
      script.onload = () => window.kakao.maps.load(() => resolve(window.kakao.maps))
      script.onerror = () => reject(new Error('카카오맵을 불러오지 못했습니다.'))
      document.head.appendChild(script)
    })
  }
  return sdk
}

function dot(maps, map, position, color, size) {
  const content = document.createElement('span')
  content.style.cssText = `display:block;width:${size}px;height:${size}px;border-radius:50%;background:${color};border:2px solid #fff;box-shadow:0 0 0 1px rgba(0,0,0,.35)`
  return new maps.CustomOverlay({ map, position, content, xAnchor: 0.5, yAnchor: 0.5, clickable: false })
}

/** 지도를 눌러 하차 지점을 고른다. */
export default function MapPicker({ hub, value, onPick }) {
  const container = useRef(null)
  const view = useRef(null)
  const marker = useRef(null)
  const pick = useRef(onPick)
  const [error, setError] = useState(APP_KEY ? '' : '카카오 JavaScript 키가 없어 지도를 표시할 수 없습니다.')
  const [ready, setReady] = useState(0)
  pick.current = onPick

  useEffect(() => {
    if (!hub || !APP_KEY) return undefined
    let closed = false
    loadKakaoMaps()
      .then((maps) => {
        if (closed) return
        const center = new maps.LatLng(hub.lat, hub.lon)
        const map = new maps.Map(container.current, { center, level: INITIAL_LEVEL })
        map.addControl(new maps.ZoomControl(), maps.ControlPosition.RIGHT)
        dot(maps, map, center, '#4a5166', 16)
        maps.event.addListener(map, 'click', (event) => {
          pick.current(event.latLng.getLat(), event.latLng.getLng())
        })
        view.current = { maps, map }
        setReady((n) => n + 1)
      })
      .catch((e) => setError(e.message))
    return () => {
      closed = true
      view.current = null
      marker.current = null
      if (container.current) container.current.innerHTML = ''
    }
  }, [hub?.id])

  useEffect(() => {
    if (!view.current) return
    const { maps, map } = view.current
    if (marker.current) {
      marker.current.setMap(null)
      marker.current = null
    }
    if (value) {
      marker.current = dot(maps, map, new maps.LatLng(value.lat, value.lon), '#e8a317', 18)
    }
  }, [value?.lat, value?.lon, ready])

  if (error) return <p className="error">{error}</p>
  return <div ref={container} className="map" role="application" aria-label="하차 지점 지도" />
}
