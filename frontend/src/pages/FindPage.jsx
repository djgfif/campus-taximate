import React, { useState } from 'react'
import { ArrowRight, MapPin } from 'lucide-react'
import { BoardSection } from '../Board.jsx'
import { Say, Screen, TopBar, useWide } from '../ui.jsx'

/**
 * 동승 찾기 페이지 (/app/find).
 * 자동 매칭 새 요청 진입과 조건이 맞는 기존 일행 직접 합류를 한 곳에 제공한다.
 */
export default function FindPage({ state, config, onStart, onChanged, tabBar }) {
  const [direction, setDirection] = useState('FROM_HUB')
  const wide = useWide()
  const blocked = state?.restricted
  const toSchool = direction === 'TO_SCHOOL'

  return (
    <Screen tabBar={tabBar}>
      {!wide && <TopBar />}
      <div className="find-page">
        <header className="find-head">
          <h1 className="head">동승 찾기</h1>
          <p className="sub">자동 매칭 요청을 등록하거나, 바로 합류할 수 있는 일행을 확인해 보세요.</p>
        </header>

        {blocked && (
          <Say tone="stop-tone" title="새 동승 요청이 제한되어 있어요">
            {state?.restriction?.message || '결제 또는 이전 미정산 내역을 확인해 주세요.'}
          </Say>
        )}

        <section className="find-launch-grid" aria-label="새 자동 매칭 요청">
          <button type="button" className="find-launch-card primary" disabled={blocked} onClick={() => onStart({ direction: 'FROM_HUB' })}>
            <span className="find-launch-kicker">추천 · 공통 승차</span>
            <strong><MapPin size={18} aria-hidden="true" />하교 동승 요청</strong>
            <p>학교 앞 승차 지점에서 같은 방향 학생과 함께 타고 각자 목적지에서 내려요.</p>
            <span className="find-launch-cta">요청 시작 <ArrowRight size={15} aria-hidden="true" /></span>
          </button>

          <button type="button" className="find-launch-card" disabled={blocked} onClick={() => onStart({ direction: 'TO_SCHOOL' })}>
            <span className="find-launch-kicker">거점 출발</span>
            <strong><MapPin size={18} aria-hidden="true" />등교 동승 요청</strong>
            <p>지정 승차 지점에서 함께 출발해 학교로 이동해요.</p>
            <span className="find-launch-cta">요청 시작 <ArrowRight size={15} aria-hidden="true" /></span>
          </button>
        </section>

        <section className="home-card" aria-label="직접 합류 가능한 일행">
          <div className="find-board-head">
            <div>
              <h2 className="section-title">직접 합류할 수 있는 일행</h2>
              <p className="note">경로와 시간·절약 조건을 만족할 때만 합류가 확정돼요.</p>
            </div>
            <div className="seg find-seg" role="tablist" aria-label="합류 방향">
              <button type="button" role="tab" aria-selected={!toSchool} className={!toSchool ? 'on' : ''} onClick={() => setDirection('FROM_HUB')}>하교</button>
              <button type="button" role="tab" aria-selected={toSchool} className={toSchool ? 'on' : ''} onClick={() => setDirection('TO_SCHOOL')}>등교</button>
            </div>
          </div>

          <BoardSection
            now={state?.now}
            config={config}
            onJoined={onChanged}
            disabled={blocked}
            direction={direction}
            onSwitch={() => setDirection(toSchool ? 'FROM_HUB' : 'TO_SCHOOL')}
          />
        </section>
      </div>
    </Screen>
  )
}
