import React, { useState } from 'react'
import { ChevronRight, MapPin } from 'lucide-react'
import { BoardSection } from '../Board.jsx'
import { defaultDirection } from '../format.js'
import { Say, Screen, TopBar, useWide } from '../ui.jsx'

/**
 * 동승 찾기 페이지 (/app/find).
 * 홈과 동일한 등교·하교 방향 탐색 UX 위에서 새 요청 진입과 직접 합류 가능한 일행을 제공한다.
 */
export default function FindPage({ state, config, onStart, onChanged, tabBar }) {
  const [direction, setDirection] = useState(() => defaultDirection(state?.now))
  const wide = useWide()
  const blocked = state?.restricted
  const toSchool = direction === 'TO_SCHOOL'

  return (
    <Screen tabBar={tabBar}>
      {!wide && <TopBar />}
      <div className="find-page">
        <header className="find-head">
          <h1 className="head">동승 찾기</h1>
        </header>

        {blocked && (
          <Say tone="stop-tone" title="새 동승 요청이 제한되어 있어요">
            {state?.restriction?.message || '결제 또는 이전 미정산 내역을 확인해 주세요.'}
          </Say>
        )}

        <section className="home-hero" aria-label="새 동승 요청">
          <div className="dir-tabs" role="tablist" aria-label="합류 방향">
            <button type="button" role="tab" aria-selected={toSchool} className={toSchool ? 'dir-tab on' : 'dir-tab'} onClick={() => setDirection('TO_SCHOOL')}>등교</button>
            <button type="button" role="tab" aria-selected={!toSchool} className={!toSchool ? 'dir-tab on' : 'dir-tab'} onClick={() => setDirection('FROM_HUB')}>하교</button>
          </div>

          <button type="button" className="home-search-btn" disabled={blocked} onClick={() => onStart({ direction })}>
            <span className="home-search-left">
              <MapPin size={18} aria-hidden="true" />
              <span>{toSchool ? '어디서 출발하세요?' : '어디로 가세요?'}</span>
            </span>
            <ChevronRight className="home-search-arrow" size={18} aria-hidden="true" />
          </button>
        </section>

        <section className="home-section" aria-label="직접 합류 가능한 일행">
          <div className="home-section-head">
            <h2 className="section-title">지금 합류할 수 있는 일행</h2>
            <span className="note">조건이 맞으면 바로 합류해요</span>
          </div>
          <div className="surface-box surface-pad">
            <BoardSection
              title=""
              now={state?.now}
              config={config}
              onJoined={onChanged}
              disabled={blocked}
              direction={direction}
              onSwitch={() => setDirection(toSchool ? 'FROM_HUB' : 'TO_SCHOOL')}
            />
          </div>
        </section>
      </div>
    </Screen>
  )
}
