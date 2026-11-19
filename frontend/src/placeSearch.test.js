import { afterEach, describe, expect, it, vi } from 'vitest'
import { PLACE_SEARCH_URL, searchPlaces } from './MapPicker.jsx'

afterEach(() => vi.restoreAllMocks())

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

  it('결과 없음은 빈 목록, 오류와 키 없음은 실패로 구분한다', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({ documents: [] }), { status: 200 }))
      .mockResolvedValueOnce(new Response('{}', { status: 401 }))
    await expect(searchPlaces('없는곳', null, 'js-key')).resolves.toEqual([])
    await expect(searchPlaces('포천시청', null, 'js-key')).rejects.toThrow('장소 검색에 실패했어요.')
    await expect(searchPlaces('포천시청', null, '')).rejects.toThrow('장소 검색 키가 없어요.')
  })
})
