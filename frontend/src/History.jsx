import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { REQUEST_LABEL, formatDateTime } from './format.js'

export default function History({ onLogoutAll }) {
  const [items, setItems] = useState(null)
  useEffect(() => {
    api.history().then(setItems).catch(() => setItems([]))
  }, [])
  if (!items) return <p className="muted">불러오는 중</p>
  const logoutAll = <button className="link" onClick={onLogoutAll}>모든 기기에서 로그아웃</button>
  if (items.length === 0) return <section className="card"><p className="muted">이용 내역이 없습니다.</p>{logoutAll}</section>
  return (
    <section className="card">
      <h2>이용 내역</h2>
      <ul className="history">
        {items.map((item) => (
          <li key={item.id}>
            <div>{item.hubName} → {item.dropoffName}</div>
            <div className="muted">{formatDateTime(item.windowStart)} · {REQUEST_LABEL[item.status]}</div>
          </li>
        ))}
      </ul>
      {logoutAll}
    </section>
  )
}
