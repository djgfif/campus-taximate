import React, { useState } from 'react'
import { api } from './api.js'

export default function AuthScreen({ config, onLogin }) {
  const [step, setStep] = useState('email')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')

  const sendCode = async (event) => {
    event.preventDefault()
    setError('')
    try {
      const result = await api.requestCode({ email })
      setStep('code')
      if (result?.demoCode) {
        setCode(result.demoCode)
        setInfo('시연 계정이라 코드를 자동으로 넣었습니다.')
      } else {
        setInfo('학교 메일로 받은 6자리 코드를 입력해 주세요. 10분 동안 유효합니다.')
      }
    } catch (e) {
      setError(e.message)
    }
  }

  const login = async (event) => {
    event.preventDefault()
    setError('')
    try {
      await api.login({ email, code })
      onLogin()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <section className="card">
      <h2>학교 메일로 시작하기</h2>
      <p className="muted">비밀번호 없이 메일로 받은 코드로 로그인합니다. 같은 기기에서는 다시 받지 않아도 됩니다.</p>
      {info && <p className="notice">{info}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {step === 'email' ? (
        <form onSubmit={sendCode}>
          <label>학교 메일
            <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" />
          </label>
          <button type="submit">코드 받기</button>
        </form>
      ) : (
        <form onSubmit={login}>
          <label>로그인 코드
            <input required inputMode="numeric" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" />
          </label>
          <button type="submit">로그인</button>
          <button type="button" className="link" onClick={() => { setStep('email'); setCode(''); setInfo(''); setError('') }}>메일 다시 입력</button>
        </form>
      )}
      {config?.demo && <p className="muted">시연 계정: demo-f1@daejin.ac.kr, demo-m1@daejin.ac.kr</p>}
    </section>
  )
}
