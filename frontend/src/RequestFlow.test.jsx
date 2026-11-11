import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RequestForm } from './RequestFlow.jsx'
import { api } from './api.js'

vi.mock('./MapView.jsx', () => ({ default: () => <div>지도</div> }))
vi.mock('./MapPicker.jsx', () => ({ default: () => <div>위치 고르기</div>, searchPlaces: vi.fn().mockResolvedValue([]) }))
const hubs = [{ id: 1, name: '정문', lat: 37.8, lon: 127.1 }]
const nearby = [1, 2, 3].map((id) => ({ id, name: '승차 지점 ' + id, walkSeconds: id * 60, walkEstimated: true }))
const props = { now: '2026-11-11T00:00:00Z', config: { demo: true, sampleDropoffs: [{ name: '역전', lat: 37.9, lon: 127.2 }] }, run: vi.fn().mockResolvedValue(), onClose: vi.fn() }

beforeEach(() => {
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  vi.spyOn(api, 'hubs').mockResolvedValue(hubs)
  vi.spyOn(api, 'school').mockResolvedValue({ name: '학교', lat: 37.8, lon: 127.1 })
  vi.spyOn(api, 'nearbyPoints').mockResolvedValue(nearby)
  vi.spyOn(api, 'previewRequest').mockResolvedValue({ soloFare: 16800, soloMinutes: 30, resultBy: '2026-11-11T00:05:00Z' })
  vi.spyOn(api, 'createRequest').mockResolvedValue({ id: 1 })
  props.run.mockClear(); props.onClose.mockClear()
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })
async function chooseOrigin() {
  fireEvent.change(await screen.findByLabelText('시연 출발지'), { target: { value: '0' } })
}
async function chooseDestination() {
  fireEvent.change(screen.getByLabelText('시연 도착지'), { target: { value: '0' } })
}

describe('새 탑승 요청', () => {
  it('근처 지점이 0곳이면 안내하고 요청할 수 없다', async () => {
    api.nearbyPoints.mockResolvedValue([])
    render(<RequestForm {...props} />)
    await chooseOrigin(); await chooseDestination()
    await screen.findByText('근처에 승차 지점이 없어요')
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    expect(screen.getByRole('button', { name: '일행 찾기' }).disabled).toBe(true)
    expect(api.previewRequest).not.toHaveBeenCalled()
    expect(api.createRequest).not.toHaveBeenCalled()
  })

  it('3곳을 선택하면 추정 걷기 시간과 미리보기를 보이고 출발지와 함께 보낸다', async () => {
    render(<RequestForm {...props} />)
    await chooseOrigin(); await chooseDestination()
    const choices = await screen.findAllByRole('checkbox')
    expect(choices).toHaveLength(3)
    for (const choice of choices) fireEvent.click(choice)
    expect(screen.getByText('걸어서 약 3분, 추정')).toBeTruthy()
    await screen.findByText('약 16,800원, 30분')
    expect(api.nearbyPoints).toHaveBeenCalledWith({ lat: 37.8, lon: 127.1 })
    fireEvent.click(screen.getByRole('button', { name: '일행 찾기' }))
    await waitFor(() => expect(api.createRequest).toHaveBeenCalledOnce())
    const body = api.createRequest.mock.calls[0][0]
    expect(body).toMatchObject({ direction: 'FROM_HUB', mode: 'RESERVATION', timeBasis: 'DEPART', origin: { lat: 37.8, lon: 127.1, name: '정문' }, pointIds: [1, 2, 3], pointName: '역전' })
    expect(new Date(body.windowEnd) - new Date(body.windowStart)).toBe(60 * 60000)
    expect(new Date(body.windowStart) - new Date(props.now)).toBeGreaterThanOrEqual(25 * 60000)
    expect(props.onClose).toHaveBeenCalledOnce()
  })

  it('즉시 선택 시 25분 안내와 즉시 요청값을 사용한다', async () => {
    render(<RequestForm {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '즉시', exact: true }))
    expect(screen.getByText('지금부터 25분 뒤 출발 가능')).toBeTruthy()
    await chooseOrigin(); await chooseDestination()
    fireEvent.click((await screen.findAllByRole('checkbox'))[0])
    await waitFor(() => expect(api.previewRequest).toHaveBeenCalledWith(expect.objectContaining({ mode: 'INSTANT' })))
  })

  it('도착 기준은 허용 10분을 기본으로 하고 출발 범위를 보내지 않는다', async () => {
    render(<RequestForm {...props} schoolDestination />)
    await chooseOrigin()
    fireEvent.click((await screen.findAllByRole('checkbox'))[0])
    expect(within(screen.getByRole('group', { name: '일찍 도착 허용' })).getByRole('button', { name: '10분' }).getAttribute('aria-pressed')).toBe('true')
    await waitFor(() => expect(api.previewRequest).toHaveBeenCalledWith(expect.objectContaining({ direction: 'FROM_HUB', timeBasis: 'ARRIVE', earlyArrivalMinutes: 10, pointName: '학교' })))
    const body = api.previewRequest.mock.calls.at(-1)[0]
    expect(body.arriveBy).toBeTruthy()
    expect(body).not.toHaveProperty('windowStart')
    expect(body).not.toHaveProperty('windowEnd')
  })

  it('출발지 좌표를 지우면 기존 승차 지점과 미리보기로 제출하지 못한다', async () => {
    render(<RequestForm {...props} />)
    await chooseOrigin(); await chooseDestination()
    fireEvent.click((await screen.findAllByRole('checkbox'))[0])
    await screen.findByText('약 16,800원, 30분')
    fireEvent.change(screen.getByLabelText('출발지', { selector: 'input' }), { target: { value: '다른 출발지' } })
    expect(screen.queryByText('약 16,800원, 30분')).toBeNull()
    expect(screen.getByRole('button', { name: '일행 찾기' }).disabled).toBe(true)
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
  })
})
