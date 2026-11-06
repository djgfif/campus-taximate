import React, { useState } from 'react'
import { api } from './api.js'

export default function AuthScreen({ config, onLogin }) {
  const [mode, setMode] = useState('login')
  const [form, setForm] = useState({ email: '', password: '', nickname: '', gender: 'FEMALE', code: '' })
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value })

  const submit = async (event) => {
    event.preventDefault()
    setError('')
    try {
      if (mode === 'login') {
        await api.login({ email: form.email, password: form.password })
        onLogin()
      } else if (mode === 'signup') {
        await api.signup({ email: form.email, password: form.password, nickname: form.nickname, gender: form.gender })
        setInfo('학교 메일로 받은 코드를 입력해 주세요.')
        setMode('verify')
      } else {
        await api.verify({ email: form.email, code: form.code })
        await api.login({ email: form.email, password: form.password })
        onLogin()
      }
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <section className="card">
      <h2>{mode === 'login' ? '로그인' : mode === 'signup' ? '학교 메일로 가입' : '메일 인증'}</h2>
      {info && <p className="notice">{info}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <form onSubmit={submit}>
        <label>학교 메일
          <input type="email" required value={form.email} onChange={set('email')} autoComplete="email" />
        </label>
        <label>비밀번호
          <input type="password" required minLength={8} value={form.password} onChange={set('password')} />
        </label>
        {mode === 'signup' && (
          <>
            <label>닉네임
              <input required maxLength={20} value={form.nickname} onChange={set('nickname')} />
            </label>
            <label>성별 (동성끼리만 묶입니다)
              <select value={form.gender} onChange={set('gender')}>
                <option value="FEMALE">여성</option>
                <option value="MALE">남성</option>
              </select>
            </label>
          </>
        )}
        {mode === 'verify' && (
          <label>인증 코드
            <input required inputMode="numeric" value={form.code} onChange={set('code')} />
          </label>
        )}
        <button type="submit">{mode === 'login' ? '로그인' : mode === 'signup' ? '인증 코드 받기' : '인증하기'}</button>
      </form>
      <button className="link" onClick={() => { setError(''); setInfo(''); setMode(mode === 'login' ? 'signup' : 'login') }}>
        {mode === 'login' ? '처음이면 가입하기' : '로그인으로 돌아가기'}
      </button>
      {config?.demo && <p className="muted">시연 계정: demo-f1@daejin.ac.kr, demo-m1@daejin.ac.kr / demo1234</p>}
    </section>
  )
}
