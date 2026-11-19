import React, { useState } from 'react'
import { api } from './api.js'
import { toast } from './ui.jsx'

const accounts = ['demo-f1', 'demo-f2', 'demo-f3', 'demo-f4', 'demo-m1', 'demo-m2', 'demo-m3', 'demo-m4', 'demo-admin']
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
    <h2>시연 계정으로 바로 들어가기</h2>
    <div className="demo-accounts">{accounts.map((account) => <button type="button" key={account} aria-label={account} disabled={busy} onClick={() => login(account)}>{account.replace('demo-', '')}</button>)}</div>
  </section>
}
