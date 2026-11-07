import { describe, expect, it } from 'vitest'
import { remaining, routeLabel, savedTotal } from './format.js'

describe('remaining', () => {
  const now = '2026-11-09T00:00:00Z'
  it('한 시간 미만은 분으로 보여 준다', () => {
    expect(remaining('2026-11-09T00:30:00Z', now)).toBe('30분')
  })
  it('시간과 분을 함께 보여 준다', () => {
    expect(remaining('2026-11-09T01:23:00Z', now)).toBe('1시간 23분')
  })
  it('지난 시각은 0분이다', () => {
    expect(remaining('2026-11-08T23:00:00Z', now)).toBe('0분')
  })
})

describe('savedTotal', () => {
  it('정산이 끝난 건만 더하고 손해는 빼지 않는다', () => {
    expect(savedTotal([
      { stage: 'SETTLED', soloFare: 13500, paid: 9900 },
      { stage: 'SETTLED', soloFare: 5000, paid: 6000 },
      { stage: 'SETTLING', soloFare: 9000, paid: 1000 },
    ])).toBe(3600)
  })
})

describe('routeLabel', () => {
  it('방향에 맞게 출발지를 앞에 둔다', () => {
    expect(routeLabel({ direction: 'TO_SCHOOL', pointName: '역전', anchorName: '정문' })).toBe('역전에서 정문')
    expect(routeLabel({ direction: 'FROM_HUB', pointName: '역전', anchorName: '정문' })).toBe('정문에서 역전')
  })
})
