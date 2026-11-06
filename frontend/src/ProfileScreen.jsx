import React, { useState } from 'react'
import { api } from './api.js'

/** 첫 로그인 뒤 한 번만 입력한다. */
export default function ProfileScreen({ onDone }) {
  const [nickname, setNickname] = useState('')
  const [gender, setGender] = useState('FEMALE')
  const [error, setError] = useState('')

  const submit = async (event) => {
    event.preventDefault()
    setError('')
    try {
      await api.profile({ nickname, gender })
      onDone()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <section className="card">
      <h2>처음 오셨네요</h2>
      {error && <p className="error" role="alert">{error}</p>}
      <form onSubmit={submit}>
        <label>닉네임 (일행에게 보입니다)
          <input required maxLength={20} value={nickname} onChange={(e) => setNickname(e.target.value)} />
        </label>
        <label>성별 (동성끼리만 매칭됩니다)
          <select value={gender} onChange={(e) => setGender(e.target.value)}>
            <option value="FEMALE">여성</option>
            <option value="MALE">남성</option>
          </select>
        </label>
        <button type="submit">시작하기</button>
      </form>
    </section>
  )
}
