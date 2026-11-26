import React from 'react'
import { ArrowLeft } from 'lucide-react'
import { Brand } from '../ui.jsx'

/**
 * 관리자 전용 업무 포털 레이아웃.
 * PC에서는 240px 좌측 사이드바 + 와이드 본문으로 520px 모바일 제약을 해제한다.
 */
export default function AdminLayout({ tabs, activeTab, onSelectTab, onBack, children }) {
  return (
    <div className="admin-shell">
      <header className="admin-topbar">
        <div className="admin-topbar-left">
          <Brand />
          <span className="admin-badge">운영 포털</span>
          <span className="admin-topbar-note">모의 운영 장부예요 · 실제 계좌 송금은 연동되지 않아요</span>
        </div>
        <button type="button" className="admin-back-btn" onClick={onBack}>
          <ArrowLeft size={15} aria-hidden="true" />
          <span>학생 화면</span>
        </button>
      </header>
      <div className="admin-body">
        <aside className="admin-sidebar">
          <nav className="admin-nav-list" aria-label="관리 업무">
            {tabs.map(([id, label]) => (
              <button
                key={id}
                type="button"
                className="admin-nav-item"
                aria-current={activeTab === id ? 'page' : undefined}
                onClick={() => onSelectTab(id)}
              >
                {label}
              </button>
            ))}
          </nav>
        </aside>
        <section className="admin-content">
          <div className="admin-content-inner">
            {children}
          </div>
        </section>
      </div>
    </div>
  )
}
