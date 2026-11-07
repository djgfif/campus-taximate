import { expect, test } from '@playwright/test'

// 1x1 PNG. 서버가 파일 앞부분으로 형식을 확인한다.
const PNG = Buffer.from('89504e470d0a1a0a0000000d4948445200000001000000010806000000'
  + '1f15c4890000000d4944415478da63f8ffff3f0005fe02fea7d6a4c50000000049454e44ae426082', 'hex')

const ACCOUNTS = { pc: ['demo-f3', 'demo-f4'], phone: ['demo-m3', 'demo-m4'] }

async function login(page, id) {
  await page.goto('/')
  await page.getByPlaceholder('학번@daejin.ac.kr').fill(`${id}@daejin.ac.kr`)
  await page.getByRole('button', { name: '코드 받기' }).click()
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: '어디로 가세요?' })).toBeVisible()
}

async function demo(page, action) {
  await page.getByRole('button', { name: '시연', exact: true }).click()
  await page.getByRole('dialog', { name: '시연' }).getByRole('button', { name: action }).click()
  await expect(page.getByRole('dialog', { name: '시연' })).toBeHidden()
}

test('등교 요청부터 정산 완료까지 화면으로 끝낸다', async ({ page }, info) => {
  await login(page, ACCOUNTS[info.project.name][0])

  await page.getByRole('button', { name: /^등교/ }).click()
  await expect(page).toHaveURL(/\/request\/school$/)
  await page.getByLabel('시연 지점').selectOption({ label: '역전 버스 정류장' })
  await expect(page.getByText(/^약 [\d,]+원, \d+분$/)).toBeVisible()
  await page.getByRole('button', { name: '일행 찾기' }).click()
  await expect(page.getByText('결과는 늦어도')).toBeVisible()

  await demo(page, '일행 만들기')
  await expect(page.getByText('내가 결제자')).toBeVisible()

  await page.getByRole('button', { name: '기사님께 보여 줄 화면' }).click()
  await expect(page.getByRole('dialog', { name: '기사님께 보여 줄 화면' })).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: '택시 탔어요' }).click()
  await demo(page, '샘플 일행 행동')
  const amount = page.getByLabel('실제 결제 금액')
  // 내일 아침 승차라 1시간씩 넘겨 학교 도착 뒤까지 간다.
  await demo(page, '+30분')
  for (let i = 0; i < 40 && !(await amount.isVisible()); i++) {
    await page.request.post('/api/demo/advance', { data: { minutes: 60 } })
    await page.waitForTimeout(300)
  }
  await amount.fill('20000')
  await page.locator('input[type=file]').setInputFiles({ name: 'r.png', mimeType: 'image/png', buffer: PNG })
  await page.getByLabel('은행').fill('시연은행')
  await page.getByLabel('받을 계좌').fill('000-000-000000')
  await page.getByRole('button', { name: /정산 요청/ }).click()
  await expect(page.getByText('받을 돈')).toBeVisible()

  await demo(page, '샘플 일행 행동')
  const received = page.getByRole('button', { name: '받음', exact: true })
  await expect(received.first()).toBeVisible()
  while (await received.count()) {
    await received.first().click()
    await page.waitForTimeout(400)
  }

  await page.goto('/history')
  await expect(page.getByText('정산 완료').first()).toBeVisible()
  await expect(page.getByText(/전체 정산 \d+건 중/)).toBeVisible()
})

test('주소로 이동하고 뒤로 가기가 앱 안에서 동작한다', async ({ page }, info) => {
  await page.goto('/history')
  await expect(page.getByRole('heading', { name: /학교 메일로/ })).toBeVisible()

  await login(page, ACCOUNTS[info.project.name][1])
  await page.getByRole('button', { name: /^하교/ }).click()
  await expect(page).toHaveURL(/\/request\/home$/)
  await page.goBack()
  await expect(page.getByRole('heading', { name: '어디로 가세요?' })).toBeVisible()
  await page.goto('/history')
  await expect(page.getByText('아직 이용 내역이 없어요')).toBeVisible()
})
