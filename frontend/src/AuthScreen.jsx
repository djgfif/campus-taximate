import React, { useState } from 'react'
import DemoAccounts from './DemoAccounts.jsx'
import { api } from './api.js'
import { Brand, Btn, Say, Screen, toast } from './ui.jsx'

/** 첫 화면: 로고, 이 서비스가 하는 일, 지금 할 일. */
function AuthHero({ title, lead }) {
  return <section className="auth-hero">
    <Brand />
    <h1 className="auth-title">{title}</h1>
    {lead && <p>{lead}</p>}
  </section>
}

/** 평소에는 메일과 비밀번호로 로그인한다. 가입과 비밀번호 재설정만 학교 메일 코드로 인증한다. */
/** 학교가 하나면 그 학교 이름과 메일 예시를, 여럿이면 일반 문구를 쓴다. 학교 이름은 서버 설정에서 온다. */
function schoolCopy(config) {
  const schools = config?.schools || []
  const only = schools.length === 1 ? schools[0] : null
  return {
    lead: only ? `${only.name} 메일로 로그인하세요.` : '학교 메일로 로그인하세요.',
    placeholder: only ? `학번@${only.emailDomain}` : '학교 메일 주소',
  }
}

export default function AuthScreen({ config, onLogin }) {
  const copy = schoolCopy(config)
  const [step, setStep] = useState('signin')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [info, setInfo] = useState('')
  const [purpose, setPurpose] = useState('join')

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
        setInfo('시연 서버는 메일을 보내지 않아 코드를 바로 넣었어요.')
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
      <input type="email" value={email} autoComplete="email" placeholder={copy.placeholder}
        onChange={(e) => setEmail(e.target.value)} />
    </label>
  )

  if (step === 'signin') {
    return (
      <Screen foot={(
        <>
          <Btn disabled={!email.includes('@') || password.length < 8} onClick={signIn}>로그인</Btn>
          <div className="auth-links">
            <button type="button" onClick={() => { setPurpose('join'); setStep('email') }}>회원가입</button>
            <button type="button" onClick={() => { setPurpose('reset'); setStep('email') }}>비밀번호 찾기</button>
          </div>
        </>
      )}>
        <AuthHero title={<>같은 방향 학생과<br />택시비를 나눠요</>} lead={copy.lead} />
        <div className="pad auth-form">
          {emailField}
          <label className="field">비밀번호
            <input type="password" value={password} autoComplete="current-password"
              onChange={(e) => setPassword(e.target.value)} />
          </label>
          {/* 시연 계정은 로그인 화면에 늘어놓지 않고 한 줄로 접어 둔다 */}
          {config?.demo && <details className="demo-entry"><summary>시연 계정으로 들어가기</summary><DemoAccounts onLogin={() => onLogin(false)} /></details>}
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
      <AuthHero title={coding ? <>인증 코드를<br />확인해 주세요</> : purpose === 'reset' ? <>비밀번호를<br />다시 정해요</> : <>학교 메일로<br />가입해요</>} />
      <div className="pad auth-form">
        <p className="sub">{coding ? email : purpose === 'reset' ? '학교 메일로 받은 코드로 본인인지 확인해요.' : '학교 메일로 받은 코드로 이 학교 학생인지 확인해요.'}</p>
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
