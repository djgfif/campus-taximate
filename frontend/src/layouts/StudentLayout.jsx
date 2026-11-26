import React from 'react'
import { User } from 'lucide-react'
import { Brand, useWide } from '../ui.jsx'

/**
 * 학생용 반응형 레이아웃.
 * PC(1024px+): 상단 1280px 헤더 (홈 / 동승 찾기 / 내 일행 / 메시지 / 이용 내역 + 관리자 권한 시 관리자 + 우측 내 계정).
 */
export default function StudentLayout({ tab, onTab, onMenu, name, unread = 0, admin = false, children }) {
  const wide = useWide()
  const navItem = (id, label) => (
    <button
      type="button"
      className={tab === id ? 'on' : ''}
      aria-current={tab === id ? 'page' : undefined}
      onClick={() => onTab(id)}
    >
      {label}
      {id === 'messages' && unread > 0 && (
        <span className="unread-badge" aria-label={`안 읽은 메시지 ${unread}개`}>
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </button>
  )

  return (
    <div className="student-shell">
      {wide && (
        <header className="student-nav">
          <div className="student-nav-inner">
            <Brand />
            <nav className="student-nav-links" aria-label="주요 메뉴">
              {navItem('home', '홈')}
              {navItem('find', '동승 찾기')}
              {navItem('rides', '내 일행')}
              {navItem('messages', '메시지')}
              {navItem('history', '이용 내역')}
              {admin && navItem('admin', '관리자')}
            </nav>
            <span className="student-nav-spacer" />
            <div className="student-nav-actions">
              <button type="button" className="student-account-btn" aria-label="내 정보" onClick={onMenu}>
                <User size={16} aria-hidden="true" />
                <span>{name}</span>
              </button>
            </div>
          </div>
        </header>
      )}
      <div className="student-main">{children}</div>
    </div>
  )
}
