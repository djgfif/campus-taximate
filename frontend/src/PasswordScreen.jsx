import React, { useState } from 'react'
import { api } from './api.js'
import { Brand, Btn, Screen, toast } from './ui.jsx'

/** 메일 인증 뒤 비밀번호를 정한다. 가입과 재설정에 같이 쓴다. */
export default function PasswordScreen({ onDone }) {
  const [password, setPassword] = useState('')
  const [again, setAgain] = useState('')
  const ok = password.length >= 8 && password.length <= 64 && password === again

  const submit = async () => {
    try {
      await api.setPassword({ password })
      await onDone()
    } catch (e) {
      toast(e.message, 'bad')
    }
  }

  return (
    <Screen foot={<Btn disabled={!ok} onClick={submit}>비밀번호 정하기</Btn>}>
      <header className="bar"><Brand /></header>
      <div className="pad">
        <h1 className="head">로그인에 쓸<br />비밀번호를 정해 주세요</h1>
        <label className="field">비밀번호 (8자 이상)
          <input type="password" value={password} autoComplete="new-password" maxLength={64}
            onChange={(e) => setPassword(e.target.value)} />
        </label>
        <label className="field">비밀번호 확인
          <input type="password" value={again} autoComplete="new-password" maxLength={64}
            onChange={(e) => setAgain(e.target.value)} />
        </label>
        {again && password !== again && <p className="note">두 비밀번호가 다릅니다.</p>}
      </div>
    </Screen>
  )
}
