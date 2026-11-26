import React from 'react'
import { Avatar, Brand, useWide } from '../ui.jsx'

/**
 * 학생용 반응형 레이아웃.
 * PC(1024px+): 상단 1280px 헤더 (홈 / 동승 찾기 / 내 일행 / 메시지 / 이용 내역 · 우측 운영/내 정보).
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
            </nav>
            <span className="student-nav-spacer" />
            <div className="student-nav-actions">
              {admin && (
                <button
                  type="button"
                  className={tab === 'admin' ? 'student-nav-admin on' : 'student-nav-admin'}
                  aria-current={tab === 'admin' ? 'page' : undefined}
                  onClick={() => onTab('admin')}
                >
                  관리자
                </button>
              )}
              <button type="button" className="student-account-btn" aria-label="내 정보" onClick={onMenu}>
                <Avatar name={name} size={28} />
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
