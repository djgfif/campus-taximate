import React from 'react'

/** 발표자 전용 시연 콘솔 격리 레이아웃 (/demo, /presenter). */
export default function DemoLayout({ onBack, children }) {
  return (
    <div className="demo-shell">
      <header className="demo-shell-bar">
        <strong>Taximate 시연 콘솔</strong>
        {onBack && (
          <button type="button" className="demo-shell-back" onClick={onBack}>
            학생 화면 열기
          </button>
        )}
      </header>
      {children}
    </div>
  )
}
