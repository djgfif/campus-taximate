import React, { useState } from 'react'
import DemoAccounts from './DemoAccounts.jsx'
import { CarTaxiFront } from 'lucide-react'
import { api } from './api.js'
import { Btn, Say, Screen, toast } from './ui.jsx'

/** 첫 화면: 흰 바탕에 택시 표시, 큰 제목, 이 서비스가 하는 일 한 줄. */
function AuthHero({ title }) {
  return <section className="auth-hero">
    <span className="auth-mark" aria-hidden="true"><CarTaxiFront size={30} strokeWidth={2.2} /></span>
    <h1 className="head">{title}</h1>
    <p>같은 방향 학생과 타고, 혼자 탈 때 요금 비율로 나눠요</p>
  </section>
}

/** 평소에는 메일과 비밀번호로 로그인한다. 가입과 비밀번호 재설정만 학교 메일 코드로 인증한다. */
export default function AuthScreen({ config, onLogin }) {
  const [step, setStep] = useState('signin')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [info, setInfo] = useState('')

  const signIn = async () => {
    try {
      await api.signin({ email, password })
      await onLogin(false)
    } catch (e) {
      toast(e.message, 'bad')
    }
  }

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

  const verify = async () => {
    try {
      await api.login({ email, code })
      await onLogin(true)
    } catch (e) {
      toast(e.message, 'bad')
    }
  }

  const back = () => { setStep('signin'); setCode(''); setInfo('') }
  const emailField = (
    <label className="field">학교 메일
      <input type="email" value={email} autoComplete="email" placeholder="학번@daejin.ac.kr"
        onChange={(e) => setEmail(e.target.value)} />
    </label>
  )

  if (step === 'signin') {
    return (
      <Screen foot={(
        <>
          <Btn disabled={!email.includes('@') || password.length < 8} onClick={signIn}>로그인</Btn>
          <Btn className="quiet" onClick={() => setStep('email')}>처음이거나 비밀번호를 잊었어요</Btn>
        </>
      )}>
        <AuthHero title={<>학교 메일로<br />로그인해요</>} />
        <div className="pad auth-form">
          {emailField}
          <label className="field">비밀번호
            <input type="password" value={password} autoComplete="current-password"
              onChange={(e) => setPassword(e.target.value)} />
          </label>
          {config?.demo && <DemoAccounts onLogin={() => onLogin(false)} />}
        </div>
      </Screen>
    )
  }

  const coding = step === 'code'
  return (
    <Screen foot={coding ? (
      <>
        <Btn disabled={code.length < 6} onClick={verify}>인증하기</Btn>
        <Btn className="quiet" onClick={back}>로그인으로 돌아가기</Btn>
      </>
    ) : (
      <>
        <Btn disabled={!email.includes('@')} onClick={sendCode}>코드 받기</Btn>
        <Btn className="quiet" onClick={back}>로그인으로 돌아가기</Btn>
      </>
    )}>
      <AuthHero title={coding ? <>메일로 받은 코드를<br />입력해 주세요</> : <>학교 메일로<br />인증해요</>} />
      <div className="pad auth-form">
        <p className="sub">{coding ? email : '가입하거나 비밀번호를 다시 정할 때 한 번 인증합니다.'}</p>
        {coding ? (
          <>
            <label className="field code">인증 코드
              <input inputMode="numeric" maxLength={6} value={code} autoComplete="one-time-code"
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
            </label>
            {info && <Say tone="calm">{info}</Say>}
          </>
        ) : emailField}
      </div>
    </Screen>
  )
}
