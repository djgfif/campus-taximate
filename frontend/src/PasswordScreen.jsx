import React, { useId, useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { api } from './api.js'
import { Brand, Btn, Screen, toast } from './ui.jsx'

/** 메일 인증 뒤 비밀번호를 정한다. 가입과 재설정에 같이 쓴다. */
export default function PasswordScreen({ onDone }) {
  const pwId = useId()
  const [password, setPassword] = useState('')
  const [again, setAgain] = useState('')
  const [showPw, setShowPw] = useState(false)
  const [busy, setBusy] = useState(false)
  const ok = password.length >= 8 && password.length <= 64 && password === again && !busy

  const submit = async () => {
    if (!ok) return
    setBusy(true)
    try {
      await api.setPassword({ password })
      await onDone()
    } catch (e) {
      toast(e.message, 'bad')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen foot={<Btn disabled={!ok} onClick={submit}>비밀번호 정하기</Btn>}>
      <header className="bar"><Brand /></header>
      <form className="pad" onSubmit={(e) => { e.preventDefault(); submit() }}>
        <h1 className="head">로그인에 쓸<br />비밀번호를 정해 주세요</h1>
        <div className="field">
          <label htmlFor={pwId}>비밀번호 (8자 이상)</label>
          <span className="input-wrap">
            <input id={pwId} type={showPw ? 'text' : 'password'} value={password} autoComplete="new-password" maxLength={64} placeholder="8자 이상"
              onChange={(e) => setPassword(e.target.value)} />
            <button type="button" className="input-action" aria-label={showPw ? '입력 내용 숨기기' : '입력 내용 보기'} aria-pressed={showPw} onClick={() => setShowPw((v) => !v)}>
              {showPw ? <EyeOff size={18} aria-hidden="true" /> : <Eye size={18} aria-hidden="true" />}
            </button>
          </span>
          {password.length > 0 && password.length < 8 && <span className="field-hint">8자 이상 입력해 주세요.</span>}
        </div>
        <label className="field">비밀번호 확인
          <input type={showPw ? 'text' : 'password'} value={again} autoComplete="new-password" maxLength={64} placeholder="비밀번호를 한 번 더 입력해 주세요"
            onChange={(e) => setAgain(e.target.value)} />
          {again && password !== again && <span className="field-err" role="alert">두 비밀번호가 다릅니다.</span>}
        </label>
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>
    </Screen>
  )
}
