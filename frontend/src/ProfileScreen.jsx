import React, { useState } from 'react'
import { api } from './api.js'
import { Brand, Btn, Chips, Screen, toast } from './ui.jsx'

/** 첫 로그인 뒤 한 번만 입력한다. */
export default function ProfileScreen({ onDone }) {
  const [nickname, setNickname] = useState('')
  const [gender, setGender] = useState('FEMALE')

  const submit = async () => {
    try {
      await api.profile({ nickname, gender })
      await onDone()
    } catch (e) {
      toast(e.message, 'bad')
    }
  }

  return (
    <Screen foot={<Btn disabled={!nickname.trim()} onClick={submit}>시작하기</Btn>}>
      <header className="bar"><Brand /></header>
      <div className="pad">
        <h1 className="head">일행에게 보일<br />이름을 정해 주세요</h1>
        <label className="field">닉네임
          <input maxLength={20} value={nickname} onChange={(e) => setNickname(e.target.value)} />
        </label>
        <div className="field">성별
          <Chips label="성별" value={gender} onChange={setGender} options={[
            { value: 'FEMALE', label: '여성' },
            { value: 'MALE', label: '남성' },
          ]} />
        </div>
        <p className="note">동성끼리만 매칭됩니다.</p>
      </div>
    </Screen>
  )
}
