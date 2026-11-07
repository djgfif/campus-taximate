// 앱을 닫아도 확정, 취소, 승차 안내를 받는다. 누르면 앱을 연다.
self.addEventListener('push', (event) => {
  const data = event.data ? event.data.json() : {}
  event.waitUntil(self.registration.showNotification(data.title || '택시메이트', {
    body: data.body || '',
    icon: '/icon.svg',
    data: { url: data.url || '/' },
  }))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
    const open = list.find((client) => 'focus' in client)
    return open ? open.focus() : self.clients.openWindow(event.notification.data.url)
  }))
})
