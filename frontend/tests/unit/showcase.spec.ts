import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'

import showcaseData from '../../public/showcase-data.json'
import { handleShowcaseRequest, resetShowcaseData } from '@/showcase/runtime'

beforeAll(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(showcaseData), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })))
})

beforeEach(async () => {
  await resetShowcaseData()
})

describe('showcase runtime', () => {
  it('uses the fixed synthetic portfolio manifest', () => {
    expect(showcaseData.manifest).toMatchObject({
      profile: 'showcase',
      seed: 42,
      synthetic: true,
      inferenceMode: 'RULE_FALLBACK_AND_TEMPLATE_DIAGNOSIS',
      entityCounts: {
        students: 96,
        classes: 4,
        courses: 6,
        teachers: 3,
        counselors: 1,
        administrators: 1,
      },
      restrictedSourceRows: 0,
      externalKeysRequired: false,
    })
  })

  it.each([
    ['demo.student', 'STUDENT'],
    ['demo.teacher', 'TEACHER'],
    ['demo.counselor', 'COUNSELOR'],
    ['demo.admin', 'ADMIN'],
  ])('enters the %s role and keeps role switching available', async (username, role) => {
    const response = await handleShowcaseRequest('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password: 'showcase' }),
    })
    const session = response.body as { user: { activeRole: string; availableRoles: string[] } }
    expect(response.status).toBe(200)
    expect(session.user.activeRole).toBe(role)
    expect(session.user.availableRoles).toEqual(['STUDENT', 'TEACHER', 'COUNSELOR', 'ADMIN'])
  })

  it('persists practice submissions in the browser session', async () => {
    const twinBefore = await handleShowcaseRequest('/courses/course-1/students/showcase-reviewer/twin/current')
    const planBefore = await handleShowcaseRequest('/courses/course-1/students/showcase-reviewer/learning-plans/current')
    const questionResponse = await handleShowcaseRequest('/courses/course-1/practice/next')
    const question = questionResponse.body as { questionId: string; choices: Array<{ choiceId: string }> }
    const submitted = await handleShowcaseRequest('/courses/course-1/answers', {
      method: 'POST',
      body: JSON.stringify({
        questionId: question.questionId,
        selectedChoiceId: question.choices[0].choiceId,
        occurredAt: '2026-07-01T08:00:00.000Z',
      }),
    })
    const history = await handleShowcaseRequest('/courses/course-1/answers?page=0&size=20')
    const twinAfter = await handleShowcaseRequest('/courses/course-1/students/showcase-reviewer/twin/current')
    const planAfter = await handleShowcaseRequest('/courses/course-1/students/showcase-reviewer/learning-plans/current')
    await handleShowcaseRequest('/courses/course-1/students/showcase-reviewer/learning-plans', {
      method: 'POST',
      body: JSON.stringify({ reason: 'MANUAL_REFRESH' }),
    })
    const regeneratedPlan = await handleShowcaseRequest('/courses/course-1/students/showcase-reviewer/learning-plans/current')
    const before = twinBefore.body as { snapshotVersion: number; mastery: Array<{ probability: number }>; risk: { probability: number } }
    const after = twinAfter.body as { snapshotVersion: number; mastery: Array<{ probability: number }>; risk: { probability: number } }
    expect((submitted.body as { status: string }).status).toBe('COMPLETED')
    expect((history.body as { total: number }).total).toBe(1)
    expect(after.snapshotVersion).toBe(before.snapshotVersion + 1)
    expect(after.mastery[0].probability).toBeGreaterThan(before.mastery[0].probability)
    expect(after.risk.probability).toBeLessThan(before.risk.probability)
    expect((planAfter.body as { version: number }).version).toBe((planBefore.body as { version: number }).version + 1)
    expect((regeneratedPlan.body as { version: number }).version).toBe((planAfter.body as { version: number }).version + 1)
  })

  it('adds student feedback while keeping admin mutations read-only', async () => {
    const actions = await handleShowcaseRequest('/risk/me/actions')
    const actionId = (actions.body as Array<{ actionItemId: string }>)[0].actionItemId
    const feedback = await handleShowcaseRequest(`/risk/me/actions/${actionId}/feedback`, {
      method: 'POST',
      body: JSON.stringify({ body: '已完成本周复习。' }),
    })
    expect((feedback.body as { feedback: unknown[] }).feedback).toHaveLength(1)

    const blocked = await handleShowcaseRequest('/admin/users/user-1', {
      method: 'DELETE',
    })
    expect(blocked.status).toBe(403)
    expect(blocked.body).toMatchObject({ code: 'SHOWCASE_READ_ONLY' })
  })
})
