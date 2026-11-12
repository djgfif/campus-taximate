import React from 'react'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api.js'
import { BoardSection } from './Board.jsx'
import { PraiseSection, trustLine } from './People.jsx'
import SketchMap from './SketchMap.jsx'

const now = '2026-11-12T09:00:00Z'
const card = {
  id: 9, direction: 'FROM_HUB', anchorName: '학교 정문', meetingPoint: '정문 앞 버스 정류장', anchorLat: 37.875, anchorLon: 127.155,
  departureAt: '2026-11-12T10:00:00Z', joinUntil: '2026-11-12T09:50:00Z', riders: 2, seats: 2, instant: false,
  people: [{ nickname: '준호', profile: '컴퓨터공학과 · 22학번' }, { nickname: '민재', profile: '' }],
}

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
})
afterEach(() => vi.restoreAllMocks())

describe('지금 모집 중', () => {
  it('같은 방향 일행을 사람과 남은 자리로 보여 주고, 내릴 곳만 골라 합류를 신청한다', async () => {
    vi.spyOn(api, 'board').mockResolvedValue({ now, parties: [card], waiting: [{ direction: 'FROM_HUB', anchorName: '학교 정문', hour: '2026-11-12T10:00:00Z', count: 3 }] })
    const join = vi.spyOn(api, 'boardJoin').mockResolvedValue({ state: 'PENDING' })
    const joined = vi.fn()
    const config = { demo: true, sampleDropoffs: [{ name: '시장 입구', lat: 37.833, lon: 127.146 }] }
    render(<BoardSection now={now} config={config} onJoined={joined} />)
    const button = await screen.findByRole('button', { name: /학교 정문에서 출발, 2명, 자리 2개/ })
    expect(within(button).getByText(/컴퓨터공학과 · 22학번/)).toBeTruthy()
    expect(screen.getByText(/하교 3명/)).toBeTruthy()
    fireEvent.click(button)
    const submit = screen.getByRole('button', { name: '합류 신청' })
    expect(submit.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText('시연 내릴 곳'), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: '합류 신청' }))
    await waitFor(() => expect(join).toHaveBeenCalledWith(9, { pointName: '시장 입구', pointLat: 37.833, pointLon: 127.146, memo: '' }))
    await waitFor(() => expect(joined).toHaveBeenCalled())
  })

  it('모인 일행이 없으면 먼저 요청하도록 안내한다', async () => {
    vi.spyOn(api, 'board').mockResolvedValue({ now, parties: [], waiting: [] })
    render(<BoardSection now={now} />)
    expect(await screen.findByText(/아직 모인 일행이 없어요/)).toBeTruthy()
  })
})

describe('같이 타는 사람', () => {
  it('신뢰 한 줄은 공개한 학과·학번, 탄 횟수, 노쇼 없음, 칭찬을 담는다', () => {
    expect(trustLine({ profile: '경영학과 · 23학번', rides: 5, noShowFree: true, praises: 3 })).toBe('경영학과 · 23학번 · 5번 탔어요 · 노쇼 없음 · 칭찬 3')
    expect(trustLine({ profile: '', rides: 0, noShowFree: true, praises: 0 })).toBe('처음 타요')
  })

  it('같이 탄 사람에게 칭찬을 골라 남긴다', async () => {
    const people = [
      { memberId: 1, nickname: '도윤', profile: '', rides: 1, noShowFree: true, praises: 0, topTags: [], myTags: [], me: true },
      { memberId: 2, nickname: '준호', profile: '컴퓨터공학과 · 22학번', rides: 4, noShowFree: true, praises: 1, topTags: [], myTags: [], me: false },
    ]
    vi.spyOn(api, 'partyPeople').mockResolvedValue(people)
    const praise = vi.spyOn(api, 'praise').mockResolvedValue([people[0], { ...people[1], praises: 3, myTags: ['KIND', 'ON_TIME'] }])
    render(<PraiseSection partyId={7} now={now} />)
    fireEvent.click(await screen.findByRole('button', { name: '칭찬하기' }))
    fireEvent.click(screen.getByRole('button', { name: '친절해요' }))
    fireEvent.click(screen.getByRole('button', { name: '시간 약속을 잘 지켜요' }))
    fireEvent.click(screen.getByRole('button', { name: '남기기' }))
    await waitFor(() => expect(praise).toHaveBeenCalledWith(7, 2, ['KIND', 'ON_TIME']))
    expect(await screen.findByText('친절해요, 시간 약속을 잘 지켜요')).toBeTruthy()
    expect(screen.queryByText('도윤')).toBeNull()
  })
})

describe('약도', () => {
  it('지도 키가 없어도 지점과 경로를 그린다', () => {
    const { container } = render(<SketchMap label="약도" points={[{ lat: 37.875, lon: 127.155, label: '출' }, { lat: 37.833, lon: 127.146, label: '도' }]}
      lines={[{ path: [{ lat: 37.875, lon: 127.155 }, { lat: 37.833, lon: 127.146 }], style: 'solid', color: '#000' }]} />)
    expect(screen.getByRole('img', { name: '약도' })).toBeTruthy()
    expect(container.querySelectorAll('circle')).toHaveLength(2)
    expect(container.querySelector('polyline')).toBeTruthy()
  })
})
