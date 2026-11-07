import React, { useEffect, useState } from 'react'
import { api } from './api.js'
import { REQUEST_LABEL, formatDateTime, routeLabel } from './format.js'
import { BackBar, Screen } from './ui.jsx'

export default function History({ onBack, onLogoutAll }) {
  const [items, setItems] = useState(null)
  useEffect(() => {
    api.history().then(setItems).catch(() => setItems([]))
  }, [])
  return (
    <Screen>
      <BackBar onBack={onBack} step="이용 내역" />
      {!items && <p className="muted">불러오는 중</p>}
      {items?.length === 0 && <p className="muted">이용 내역이 없습니다.</p>}
      <ul className="history">
        {(items || []).map((item) => (
          <li key={item.id}>
            <div>{routeLabel(item)}</div>
            <div className="muted">{formatDateTime(item.arriveBy || item.windowStart)} · {REQUEST_LABEL[item.status]}</div>
          </li>
        ))}
      </ul>
      <button className="link" onClick={onLogoutAll}>모든 기기에서 로그아웃</button>
    </Screen>
  )
}
