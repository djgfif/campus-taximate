import React, { useState } from 'react'
import { api } from './api.js'
import { Brand, Btn, Say, Screen, toast } from './ui.jsx'

export default function AuthScreen({ config, onLogin }) {
  const [step, setStep] = useState('email')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [info, setInfo] = useState('')

  const sendCode = async () => {
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
      toast(e.message, 'bad')
    }
  }

  const login = async () => {
    try {
      await api.login({ email, code })
      await onLogin()
    } catch (e) {
      toast(e.message, 'bad')
    }
  }

  const coding = step === 'code'
  return (
    <Screen foot={coding ? (
      <>
        <Btn disabled={code.length < 6} onClick={login}>로그인</Btn>
        <Btn className="quiet" onClick={() => { setStep('email'); setCode(''); setInfo('') }}>메일 다시 입력</Btn>
      </>
    ) : <Btn disabled={!email.includes('@')} onClick={sendCode}>코드 받기</Btn>}>
      <header className="bar"><Brand /></header>
      <div className="pad">
        <h1 className="head">{coding ? '메일로 받은 코드를\n입력해 주세요'.split('\n').map((line) => <span key={line}>{line}<br /></span>) : <>학교 메일로<br />시작해요</>}</h1>
        <p className="sub">{coding ? email : '비밀번호 없이 메일 코드로 로그인합니다. 같은 기기에서는 다시 받지 않아도 됩니다.'}</p>
        {coding ? (
          <>
            <label className="field code">로그인 코드
              <input inputMode="numeric" maxLength={6} value={code} autoComplete="one-time-code"
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
            </label>
            {info && <Say tone="calm">{info}</Say>}
          </>
        ) : (
          <label className="field">학교 메일
            <input type="email" value={email} autoComplete="email" placeholder="학번@daejin.ac.kr"
              onChange={(e) => setEmail(e.target.value)} />
          </label>
        )}
        {config?.demo && !coding && <p className="note">시연 계정: demo-f1@daejin.ac.kr, demo-m1@daejin.ac.kr</p>}
      </div>
    </Screen>
  )
}
