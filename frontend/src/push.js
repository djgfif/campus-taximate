import { api } from './api.js'

export const pushSupported = () =>
  typeof window !== 'undefined' && window.isSecureContext && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window

function keyBytes(base64) {
  const padded = (base64 + '='.repeat((4 - (base64.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
}

function sameKey(subscription, key) {
  const current = subscription.options?.applicationServerKey
  if (!current) return false
  const a = new Uint8Array(current)
  return a.length === key.length && a.every((value, i) => value === key[i])
}

export function registerWorker() {
  if (!pushSupported()) return
  navigator.serviceWorker.register('/sw.js').catch(() => {})
}

/** 허용을 묻고 이 기기를 등록한다. 서버 키가 바뀌었으면 새로 등록한다. */
export async function enablePush({ ask = true } = {}) {
  if (!pushSupported()) throw new Error('이 브라우저는 알림을 지원하지 않습니다.')
  const { publicKey } = await api.pushKey()
  if (!publicKey) throw new Error('이 서버는 푸시 알림을 쓰지 않습니다.')
  const permission = ask ? await Notification.requestPermission() : Notification.permission
  if (permission !== 'granted') throw new Error('브라우저 설정에서 이 사이트의 알림을 허용해 주세요.')
  const registration = await navigator.serviceWorker.ready
  const key = keyBytes(publicKey)
  let subscription = await registration.pushManager.getSubscription()
  if (subscription && !sameKey(subscription, key)) {
    await subscription.unsubscribe()
    subscription = null
  }
  subscription = subscription || await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key })
  await api.subscribePush(subscription.toJSON())
}

export async function disablePush() {
  if (!pushSupported()) return
  const registration = await navigator.serviceWorker.ready
  const subscription = await registration.pushManager.getSubscription()
  if (!subscription) return
  await api.unsubscribePush(subscription.toJSON()).catch(() => {})
  await subscription.unsubscribe()
}

export const pushGranted = () => pushSupported() && Notification.permission === 'granted'
export const pushUndecided = () => pushSupported() && Notification.permission === 'default'
