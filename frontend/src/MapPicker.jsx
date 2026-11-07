import React, { useEffect, useRef, useState } from 'react'
import { ChevronLeft, MapPin } from 'lucide-react'
import { Btn } from './ui.jsx'

const APP_KEY = import.meta.env.VITE_KAKAO_JS_KEY
const INITIAL_LEVEL = 6
const LOAD_TIMEOUT_MS = 8000

let sdk = null

/** 카카오맵 SDK를 한 번만 불러온다. */
export function loadKakaoMaps() {
  if (!sdk) {
    sdk = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${APP_KEY}&libraries=services&autoload=false`
      // 스크립트가 막히면 load 콜백이 오지 않는다. 기다리지 않고 지도 없는 화면으로 넘어간다.
      const timer = setTimeout(() => reject(new Error('카카오맵을 불러오지 못했습니다.')), LOAD_TIMEOUT_MS)
      script.onload = () => window.kakao.maps.load(() => {
        clearTimeout(timer)
        resolve(window.kakao.maps)
      })
      script.onerror = () => reject(new Error('카카오맵을 불러오지 못했습니다.'))
      document.head.appendChild(script)
    })
  }
  return sdk
}

/** 좌표의 도로명이나 지번 주소. 못 찾으면 빈 문자열이다. */
function addressOf(maps, lat, lon) {
  return new Promise((resolve) => {
    new maps.services.Geocoder().coord2Address(lon, lat, (data, status) => {
      if (status !== maps.services.Status.OK || !data[0]) {
        resolve('')
        return
      }
      const { road_address: road, address } = data[0]
      resolve(road?.building_name || road?.address_name || address?.address_name || '')
    })
  })
}

/** 지도를 움직여 가운데 핀으로 고른다. 터치 기기에서도 지도 클릭에 기대지 않는다. */
export default function MapPicker({ center: origin, value, title, onPick, onClose, embedded }) {
  const container = useRef(null)
  const [error, setError] = useState(APP_KEY ? '' : '지도 키가 없어 지도를 표시할 수 없습니다. 주소 검색을 이용해 주세요.')
  const [spot, setSpot] = useState(null)

  useEffect(() => {
    if (!origin || !APP_KEY) return undefined
    let closed = false
    let ask = 0
    loadKakaoMaps()
      .then((maps) => {
        if (closed) return
        const start = value || origin
        const map = new maps.Map(container.current, { center: new maps.LatLng(start.lat, start.lon), level: INITIAL_LEVEL })
        const update = async () => {
          const position = map.getCenter()
          const lat = position.getLat()
          const lon = position.getLng()
          ask += 1
          const mine = ask
          setSpot({ lat, lon, name: '' })
          const name = await addressOf(maps, lat, lon)
          if (!closed && mine === ask) setSpot({ lat, lon, name })
        }
        maps.event.addListener(map, 'idle', update)
        update()
      })
      .catch((e) => setError(e.message))
    return () => {
      closed = true
    }
  }, [origin?.lat, origin?.lon])

  const pick = () => onPick({ name: spot.name || '지도에서 고른 지점', lat: spot.lat, lon: spot.lon })

  if (embedded) {
    return (
      <>
        <div ref={container} className="map-canvas" role="application" aria-label="지도를 움직여 위치를 맞춰 주세요" />
        <span className="map-pin"><MapPin size={44} fill="#f5b301" strokeWidth={1.6} aria-hidden="true" /></span>
        <div className="floater">
          <div className="addr"><b>{error || spot?.name || '위치를 확인하는 중'}</b><small>지도를 움직여 핀을 맞춰 주세요</small></div>
          <Btn disabled={!spot || !!error} onClick={pick}>이 위치로 정하기</Btn>
        </div>
      </>
    )
  }

  return (
    <div className="mapsheet" role="dialog" aria-modal="true" aria-label={title}>
      <header className="bar back">
        <div className="bar-left">
          <button type="button" className="icon-btn" aria-label="닫기" onClick={onClose}><ChevronLeft size={26} /></button>
          <span className="bar-title">{title}</span>
        </div>
      </header>
      <div className="map-full">
        <div ref={container} className="map-canvas" role="application" aria-label="지도를 움직여 위치를 맞춰 주세요" />
        <span className="map-pin"><MapPin size={40} fill="#f5b301" strokeWidth={1.6} aria-hidden="true" /></span>
      </div>
      <div className="picked">
        <b>{error || spot?.name || '위치를 확인하는 중'}</b>
        <small>지도를 움직여 핀을 맞춰 주세요</small>
      </div>
      <div className="foot">
        <Btn disabled={!spot || !!error} onClick={pick}>이 위치로 정하기</Btn>
      </div>
    </div>
  )
}

/** 카카오 장소 검색. 키가 없으면 빈 목록을 돌려준다. */
export async function searchPlaces(query, center) {
  if (!APP_KEY || !query.trim()) return []
  const maps = await loadKakaoMaps()
  return new Promise((resolve) => {
    const options = center ? { location: new maps.LatLng(center.lat, center.lon), size: 5 } : { size: 5 }
    new maps.services.Places().keywordSearch(query, (data, status) => {
      if (status !== maps.services.Status.OK) {
        resolve([])
        return
      }
      resolve(data.map((place) => ({
        id: place.id,
        name: place.place_name,
        address: place.road_address_name || place.address_name,
        lat: Number(place.y),
        lon: Number(place.x),
      })))
    }, options)
  })
}
