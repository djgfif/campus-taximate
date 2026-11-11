import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PartyFareRows, PartyScreen, RideConfirmationCard } from './PartyScreens.jsx'
import { api } from './api.js'

beforeEach(()=>{
  window.matchMedia=()=>({matches:false,addEventListener(){},removeEventListener(){}})
  vi.spyOn(api,'partyMessages').mockResolvedValue({closed:true,messages:[]})
})
afterEach(()=>{cleanup();vi.restoreAllMocks()})

describe('운행 확인과 변경 이탈',()=>{
  it('탔어요를 본인 답 API로 보내고 첫 답변 뒤 두 버튼을 닫는다',async()=>{
    const confirm=vi.spyOn(api,'confirmRide').mockResolvedValue({myRideAnswer:true})
    const updated=vi.fn().mockResolvedValue()
    render(<RideConfirmationCard party={{id:11,status:'RIDE_UNCONFIRMED',canConfirmRide:true,myRideAnswer:null}} onUpdated={updated} />)
    expect(screen.getByRole('heading',{name:'운행 확인 중'})).toBeTruthy()
    expect(screen.getByRole('button',{name:'못 탔어요',exact:true})).toBeTruthy()
    fireEvent.click(screen.getByRole('button',{name:'탔어요',exact:true}))
    await waitFor(()=>expect(confirm).toHaveBeenCalledWith(11,true))
    await screen.findByText('내 답변: 탔어요')
    expect(screen.queryByRole('button',{name:'못 탔어요',exact:true})).toBeNull()
    expect(confirm).toHaveBeenCalledOnce();expect(updated).toHaveBeenCalledOnce()
  })
  it('이관 출처와 확정 때·현재 예상 분담금을 함께 표시한다',()=>{
    render(<PartyFareRows party={{myConfirmedShare:3200,myShare:4000,myConfirmedMigrated:true}} />)
    expect(screen.getByText('확정 때 예상 분담금')).toBeTruthy()
    expect(screen.getByText('3,200원')).toBeTruthy()
    expect(screen.getByText('현재 예상 분담금')).toBeTruthy()
    expect(screen.getByText('4,000원')).toBeTruthy()
    expect(screen.getByText('원본 미상(이관)')).toBeTruthy()
  })
  it('호출 뒤 변경 이탈에는 무료 아님을 안내하고 현재 일행 버전을 전송한다',async()=>{
    const leave=vi.spyOn(api,'leaveAfterChange').mockResolvedValue({freeRefund:false,message:'무료가 아니에요'})
    const run=vi.fn(async(action)=>action())
    render(<PartyScreen party={{id:11,status:'READY',prepayDeadlineAt:'2026-11-11T00:10:00Z',departureAt:'2026-11-11T00:30:00Z',freeCancelUntil:'2026-11-11T00:10:00Z',payer:true,payerNickname:'다',myRequestId:3,myShare:8000,myConfirmedShare:8000,members:[],groupVersion:2,canLeaveAfterChange:true,changeExitFree:false}} now="2026-11-11T00:20:00Z" run={run} />)
    fireEvent.click(screen.getByRole('button',{name:'변경돼서 빠지기'}))
    expect(screen.getByRole('dialog',{name:'변경된 일행에서 빠질까요?'})).toBeTruthy()
    expect(screen.getByText('무료 아님')).toBeTruthy()
    expect(screen.getByText('호출 시각이 지나 무료가 아니에요. 선결제는 환불하지 않고 정산 처리를 기다려요.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button',{name:'변경 이탈 확인'}))
    await waitFor(()=>expect(leave).toHaveBeenCalledWith(11,2))
  })
})
