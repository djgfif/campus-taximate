import React from 'react'
import { api } from './api.js'
import { formatDateTime } from './format.js'

export default function DemoBar({ now, run }) {
  return (
    <details className="demo">
      <summary>시연 모드 {now && <span className="muted">{formatDateTime(now)}</span>}</summary>
      <div className="row">
        <button className="secondary" onClick={() => run(() => api.demoSamples(2))}>샘플 요청 2개</button>
        <button className="secondary" onClick={() => run(() => api.demoRun())}>지금 매칭 실행</button>
        <button className="secondary" onClick={() => run(() => api.demoAct())}>샘플 일행 행동</button>
        <button className="secondary" onClick={() => run(() => api.demoAdvance(5))}>+5분</button>
        <button className="secondary" onClick={() => run(() => api.demoAdvance(30))}>+30분</button>
        <button className="secondary" onClick={() => run(() => api.demoAdvance(1440))}>+24시간</button>
      </div>
    </details>
  )
}
