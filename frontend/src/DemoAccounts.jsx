import React, { useState } from 'react'
import { api } from './api.js'
import { Btn, toast } from './ui.jsx'

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
  return <section aria-label="시연 계정 빠른 전환">
    <h2 className="block-title">시연 계정</h2>
    <div className="demo-accounts">{accounts.map((account) => <Btn key={account} className="small line" disabled={busy} onClick={() => login(account)}>{account}</Btn>)}</div>
  </section>
}
