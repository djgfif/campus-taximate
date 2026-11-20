import React, { useState } from 'react'
import { api } from './api.js'
import { toast } from './ui.jsx'

/** 시연 계정. 서버 시드(DataSeeder)와 같은 이름이다. 화면에는 계정 번호 대신 사람 이름을 보인다. */
export const DEMO_ACCOUNTS = [
  ['demo-m4', '현우'], ['demo-m1', '준호'], ['demo-m2', '도윤'], ['demo-m3', '민재'],
  ['demo-f1', '하은'], ['demo-f2', '서연'], ['demo-f3', '지민'], ['demo-f4', '유나'],
  ['demo-admin', '관리자'],
]

export default function DemoAccounts({ onLogin }) {
  const [busy, setBusy] = useState(false)
  const login = async (account) => {
    if (busy) return
    setBusy(true)
    try { await api.demoLogin(account); await onLogin() }
    catch (e) { toast(e.message, 'bad') }
    finally { setBusy(false) }
  }
  return <section className="demo-login" aria-label="시연 계정 빠른 전환">
    <div className="demo-accounts">{DEMO_ACCOUNTS.map(([account, name]) => <button type="button" key={account} disabled={busy} onClick={() => login(account)}>{name}</button>)}</div>
  </section>
}
