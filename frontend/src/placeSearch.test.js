import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadKakaoMaps, PLACE_SEARCH_URL, searchPlaces } from './MapPicker.jsx'
import { buildMarkerElement } from './MapView.jsx'

afterEach(() => {
  vi.restoreAllMocks()
  delete window.kakao
  document.head.querySelectorAll('script').forEach((s) => s.remove())
})

describe('장소 검색', () => {
  it('http 페이지에서도 https 로컬 API를 JavaScript 키와 KA 헤더로 부른다', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ documents: [
      { id: '8122492', place_name: '포천시청', road_address_name: '경기 포천시 중앙로 87', x: '127.2003', y: '37.8949' },
    ] }), { status: 200 }))
    const places = await searchPlaces('포천시청', { lat: 37.875, lon: 127.155 }, 'js-key')
    const [url, init] = fetch.mock.calls[0]
    expect(url.startsWith(PLACE_SEARCH_URL + '?')).toBe(true)
    expect(PLACE_SEARCH_URL.startsWith('https://')).toBe(true)
    expect(new URL(url).searchParams.get('x')).toBe('127.155')
    expect(init.headers.Authorization).toBe('KakaoAK js-key')
    expect(init.headers.KA).toMatch(/^sdk\/\S+ os\/javascript origin\//)
    expect(places).toEqual([{ id: '8122492', name: '포천시청', address: '경기 포천시 중앙로 87', lat: 37.8949, lon: 127.2003 }])
  })

  it('브라우저 기본 호출은 서버 /api/places/search 프록시를 우선 사용한다', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify([
      { id: '8122492', name: '포천시청', address: '경기 포천시 중앙로 87', lat: 37.8949, lon: 127.2003 },
    ]), { status: 200 }))
    const places = await searchPlaces('포천시청', { lat: 37.875, lon: 127.155 })
    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('/api/places/search?query=%ED%8F%AC%EC%B2%9C%EC%8B%9C%EC%B2%AD&lat=37.875&lon=127.155')
    expect(init.credentials).toBe('same-origin')
    expect(places).toEqual([{ id: '8122492', name: '포천시청', address: '경기 포천시 중앙로 87', lat: 37.8949, lon: 127.2003 }])
  })

  it('결과 없음은 빈 목록, 오류와 키 없음은 실패로 구분한다', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({ documents: [] }), { status: 200 }))
      .mockResolvedValueOnce(new Response('{}', { status: 401 }))
    await expect(searchPlaces('없는곳', null, 'js-key')).resolves.toEqual([])
    await expect(searchPlaces('포천시청', null, 'js-key')).rejects.toThrow('장소 검색에 실패했어요.')
    await expect(searchPlaces('포천시청', null, '')).rejects.toThrow('장소 검색 키가 없어요.')
  })
})

describe('카카오맵 SDK와 마커 안전성', () => {
  it('SDK 로드에 한 번 실패해도 실패한 스크립트를 치우고 다음 시도에서 다시 불러온다', async () => {
    const first = loadKakaoMaps('test-key')
    const badScript = document.head.querySelector('script')
    expect(badScript).toBeTruthy()
    badScript.onerror()
    await expect(first).rejects.toThrow('카카오맵을 불러오지 못했습니다.')
    expect(document.head.querySelector('script')).toBeNull()

    const second = loadKakaoMaps('test-key')
    const okScript = document.head.querySelector('script')
    expect(okScript).toBeTruthy()
    window.kakao = { maps: { Map: vi.fn(), load: (cb) => cb() } }
    okScript.onload()
    await expect(second).resolves.toBe(window.kakao.maps)
  })

  it('지도 마커는 HTML 문자열 대신 텍스트 노드로 만들어 태그를 해석하지 않는다', () => {
    const el = buildMarkerElement({ label: '<img src=x onerror=alert(1)>', tone: 'end' })
    expect(el.querySelector('img')).toBeNull()
    expect(el.textContent).toBe('<img src=x onerror=alert(1)>')
    expect(el.querySelector('.mk.end')).toBeTruthy()
  })
})
