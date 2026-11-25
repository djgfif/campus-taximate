import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import BoardingCodePanel from './BoardingCodePanel.jsx'
import { api } from './api.js'
afterEach(() => { cleanup(); vi.restoreAllMocks() })
const base = { generation: 3, deadlineAt: '2026-11-19T09:05:00Z', status: 'OPEN', confirmed: false, remainingAttempts: 5, canOpen: false, canStart: false }
const now = '2026-11-19T09:00:00Z'
it('결제자 화면에서 선행 0 코드를 보여 주고 명시적으로 출발한다', async () => {
 vi.spyOn(api, 'boardingState').mockResolvedValue({ ...base, code: '000042', canStart: true })
 const start = vi.spyOn(api, 'startRide').mockResolvedValue()
 const updated = vi.fn()
 render(<BoardingCodePanel party={{ id: 7, payer: true }} now={now} onUpdated={updated} />)
 expect((await screen.findByLabelText('탑승 번호')).textContent).toBe('000042')
 expect(start).not.toHaveBeenCalled()
 fireEvent.click(screen.getByRole('button', { name: '운행 시작' }))
 await waitFor(() => expect(start).toHaveBeenCalledWith(7))
 await waitFor(() => expect(updated).toHaveBeenCalledOnce())
})
it('승객은 문자열과 현재 generation을 전송한다', async () => {
 vi.spyOn(api, 'boardingState').mockResolvedValue(base)
 const confirm = vi.spyOn(api, 'confirmBoardingCode').mockResolvedValue()
 render(<BoardingCodePanel party={{ id: 7, payer: false }} now={now} />)
 fireEvent.change(await screen.findByLabelText('6자리 탑승 번호'), { target: { value: '000042' } })
 fireEvent.click(screen.getByRole('button', { name: '탑승 확인' }))
 await waitFor(() => expect(confirm).toHaveBeenCalledWith(7, '000042', 3))
 expect(screen.queryByLabelText('탑승 번호')).toBeNull()
})
it('오입력 뒤 서버의 남은 횟수를 다시 읽고 잠기면 제출을 막는다', async () => {
 const state = vi.spyOn(api, 'boardingState').mockResolvedValueOnce({ ...base, remainingAttempts: 1 }).mockResolvedValue({ ...base, remainingAttempts: 0 })
 vi.spyOn(api, 'confirmBoardingCode').mockRejectedValue(new Error('코드가 맞지 않아요'))
 render(<BoardingCodePanel party={{ id: 7, payer: false }} now={now} />)
 fireEvent.change(await screen.findByLabelText('6자리 탑승 번호'), { target: { value: '123456' } })
 fireEvent.click(screen.getByRole('button', { name: '탑승 확인' }))
 await screen.findByText('남은 입력 횟수 0회')
 expect(screen.getByRole('button', { name: '탑승 확인' }).disabled).toBe(true)
 expect(state).toHaveBeenCalledTimes(2)
})
it('만료 후 미입력을 노쇼 확정으로 안내하지 않고 조사와 소명을 설명한다', async () => {
 vi.spyOn(api, 'boardingState').mockResolvedValue({ ...base, status: 'EXPIRED' })
 render(<BoardingCodePanel party={{ id: 7, payer: false }} />)
 expect(await screen.findByText(/이유만으로 노쇼가 되지 않아요/)).toBeTruthy()
 expect(screen.queryByRole('button', { name: '탑승 확인' })).toBeNull()
})
it('시연에서 결제자가 시연용 학생이면 동승자 입력 창에 결제자 화면의 번호를 알려 준다', async () => {
 vi.spyOn(api, 'boardingState').mockResolvedValue(base)
 const hint = vi.spyOn(api, 'demoPayerCode').mockResolvedValue({ code: '004217' })
 render(<BoardingCodePanel party={{ id: 7, payer: false }} now={now} demo />)
 expect((await screen.findByText(/결제자 화면의 번호는/)).textContent).toContain('004217')
 expect(hint).toHaveBeenCalledWith(7)
})
it('시연이 아니면 결제자 번호를 묻지 않는다', async () => {
 vi.spyOn(api, 'boardingState').mockResolvedValue(base)
 const hint = vi.spyOn(api, 'demoPayerCode').mockResolvedValue({ code: '004217' })
 render(<BoardingCodePanel party={{ id: 7, payer: false }} now={now} />)
 await screen.findByLabelText('6자리 탑승 번호')
 expect(hint).not.toHaveBeenCalled()
 expect(screen.queryByText(/결제자 화면의 번호는/)).toBeNull()
})
