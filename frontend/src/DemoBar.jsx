import React, { useState } from 'react'
import { api } from './api.js'
import { formatDateTime } from './format.js'
import { Btn, Sheet } from './ui.jsx'

/** 시연에서만 보인다. 실제 사용 화면을 가리지 않도록 작은 버튼 뒤에 둔다. */
export default function DemoBar({ now, run }) {
  const [open, setOpen] = useState(false)
  const act = (action, done) => async () => {
    await run(action, done)
    setOpen(false)
  }
  return (
    <>
      <button type="button" className="demo-fab" onClick={() => setOpen(true)}>시연</button>
      {open && (
        <Sheet title="시연" className="pop demo" onClose={() => setOpen(false)}>
          {now && <p className="sub">앱 시각 {formatDateTime(now)}</p>}
          <div className="demo-grid">
            <div className="wide">
              <Btn onClick={act(() => api.demoMatch(), (r) => r.message)}>일행 만들기</Btn>
            </div>
            <div className="wide">
              <Btn className="line" onClick={act(() => api.demoAct(), '샘플 일행이 다음 행동을 했어요')}>샘플 일행 행동</Btn>
            </div>
            <Btn className="line" onClick={act(() => api.demoAdvance(5), '5분 지났어요')}>+5분</Btn>
            <Btn className="line" onClick={act(() => api.demoAdvance(30), '30분 지났어요')}>+30분</Btn>
            <Btn className="line" onClick={act(() => api.demoAdvance(1440), '24시간 지났어요')}>+24시간</Btn>
          </div>
        </Sheet>
      )}
    </>
  )
}
