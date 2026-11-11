import { expect, test } from '@playwright/test'

// 1x1 PNG. 서버가 파일 앞부분으로 형식을 확인한다.
const PNG = Buffer.from('89504e470d0a1a0a0000000d4948445200000001000000010806000000'
  + '1f15c4890000000d4944415478da63f8ffff3f0005fe02fea7d6a4c50000000049454e44ae426082', 'hex')

const ACCOUNTS = { pc: ['demo-f3', 'demo-f4'], phone: ['demo-m3', 'demo-m4'] }

async function login(page, id) {
  await page.goto('/')
  await page.getByPlaceholder('학번@daejin.ac.kr').fill(`${id}@daejin.ac.kr`)
  await page.getByLabel('비밀번호').fill('demo1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: '어디로 가세요?' })).toBeVisible({ timeout: 15000 })
}

async function demo(page, action) {
  await page.getByRole('button', { name: '시연', exact: true }).click()
  await page.getByRole('dialog', { name: '시연' }).getByRole('button', { name: action }).click()
  // 2~3명은 모집 마감까지 슬롯을 돌리므로 CI에서는 몇 초 넘게 걸릴 수 있다.
  await expect(page.getByRole('dialog', { name: '시연' })).toBeHidden({ timeout: 60000 })
}

test('승차 지점 요청부터 이의 기간과 정산 완료까지 화면으로 끝낸다', async ({ page }, info) => {
  await login(page, ACCOUNTS[info.project.name][0])
  await page.getByRole('button', { name: /^하교/ }).click()
  await expect(page).toHaveURL(/\/request\/home$/)
  await page.getByLabel('시연 출발지').selectOption({ index: 1 })
  await page.getByLabel('시연 도착지').selectOption({ label: '역전 버스 정류장' })
  await page.getByRole('checkbox').first().check()
  await expect(page.getByText(/^약 [\d,]+원, \d+분$/)).toBeVisible()
  await page.getByRole('button', { name: '일행 찾기' }).click()
  await expect(page.getByText('결과는 늦어도')).toBeVisible()

  await demo(page, '일행 만들기')
  await expect(page.getByText('내가 결제자')).toBeVisible()
  await expect(page.getByText('무료 취소 마감', { exact: true })).toBeVisible()
  // 샘플 회원의 선결제와 도착을 실제 회원과 같은 API로 처리한다.
  await demo(page, '샘플 일행 행동')
  await page.getByRole('button', { name: '기사님께 보여 줄 화면' }).click()
  await expect(page.getByRole('dialog', { name: '기사님께 보여 줄 화면' })).toBeVisible()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '도착했어요', exact: true }).click()

  const amount = page.getByLabel('실제 결제 금액')
  for (let i = 0; i < 30 && !(await amount.isVisible()); i++) await demo(page, '+5분')
  await expect(amount).toBeVisible()
  await amount.fill('20000')
  await page.getByLabel('영수증 사진').setInputFiles({ name: 'r.png', mimeType: 'image/png', buffer: PNG })
  await expect(page.getByLabel('은행', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('받을 계좌', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: /정산 요청/ }).click()
  await expect(page.getByRole('heading', { name: '이의 기간', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '요금 이의', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /^(보냄|받음)$/ })).toHaveCount(0)
  await expect(page.getByText(/모의 지급/)).toHaveCount(0)

  await demo(page, '+24시간')
  await demo(page, '+5분')
  // 확정 후 추가 결제가 생긴 샘플도 모의 게이트웨이로 승인한다.
  await demo(page, '샘플 일행 행동')
  await demo(page, '+5분')
  await page.goto('/history')
  await expect(page.getByText('정산 완료', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(/모의 지급 [\d,]+원/).first()).toBeVisible()
  await expect(page.getByText('실제 송금 없음').first()).toBeVisible()
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
