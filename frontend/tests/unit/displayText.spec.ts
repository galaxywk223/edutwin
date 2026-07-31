import { describe, expect, it } from 'vitest'

import type { Diagnosis, TwinState } from '@/shared/types/api'
import {
  analysisStatusLabel,
  auditActionLabel,
  auditOutcomeLabel,
  auditTargetLabel,
  datasetStatusLabel,
  diagnosisDisplay,
  importErrorLabel,
  importFieldLabel,
  modelPurposeLabel,
  planReasonLabel,
  planStatusLabel,
  problemMessage,
  riskFeatureLabel,
  userErrorMessage,
} from '@/shared/utils/displayText'

describe('display text helpers', () => {
  it('maps business enums without exposing unknown raw values', () => {
    expect(analysisStatusLabel('PROCESSING')).toBe('分析中')
    expect(planStatusLabel('ACTIVE')).toBe('生效中')
    expect(datasetStatusLabel('ENABLED')).toBe('已启用')
    expect(auditActionLabel('USER_CREATED')).toBe('创建用户')
    expect(auditActionLabel('AI_CONFIGURATION_ACTIVATED')).toBe('更新大模型配置')
    expect(auditTargetLabel('USER')).toBe('用户')
    expect(auditTargetLabel('AI_CONFIGURATION')).toBe('大模型配置')
    expect(auditOutcomeLabel('SUCCEEDED')).toBe('成功')
    expect(modelPurposeLabel('NEXT_CORRECT')).toBe('下一题正确率')
    expect(planReasonLabel('UNRECOGNIZED_REASON')).toBe('其他学习需要')
    expect(riskFeatureLabel('unrecognized_feature')).toBe('其他学习行为指标')
  })

  it('localizes import fields and errors', () => {
    expect(importFieldLabel('student_number')).toBe('学号')
    expect(importErrorLabel('USER_IMPORT_DUPLICATE_USERNAME')).toBe('导入文件中账号重复')
    expect(importErrorLabel('UNRECOGNIZED_IMPORT_ERROR')).toBe('导入内容不符合要求')
  })

  it('does not expose English problem details', () => {
    expect(problemMessage('INVALID_CREDENTIALS', 'The username is invalid.', 401)).toBe('账号或密码错误。')
    expect(problemMessage('UNKNOWN_CODE', 'Unexpected upstream failure.', 503)).toBe('服务正在恢复，请稍后重试。')
    expect(problemMessage('UNKNOWN_CODE', '课程状态不可用。', 409)).toBe('课程状态不可用。')
    expect(problemMessage('AI_CONFIGURATION_STALE', 'stale', 409)).toBe('配置已被其他管理员更新，请刷新后重试。')
    expect(userErrorMessage(new Error('Failed to fetch'), '服务连接失败。')).toBe('服务连接失败。')
    expect(userErrorMessage(new Error('服务连接失败。'), '其他错误。')).toBe('服务连接失败。')
  })

  it('replaces legacy English diagnosis content with Chinese text', () => {
    const diagnosis = {
      riskBand: 'LOW',
      summary: 'Current risk is LOW at 0.25.',
      strengths: ['A complete interaction history is available.'],
      concerns: ['assessment_mean_score is the largest factor.'],
      recommendedActions: ['Complete the generated practice tasks.'],
      evidence: [{ featureName: 'assessment_mean_score', direction: 'INCREASES_RISK' }],
    } as Diagnosis
    const twin = {
      risk: { riskBand: 'LOW', probability: 0.25 },
      mastery: [{ skillName: '线性代数', probability: 0.4 }],
    } as TwinState

    expect(diagnosisDisplay(diagnosis, twin)).toEqual({
      summary: '当前风险等级为低风险，风险概率为25.0%。',
      strengths: ['当前学情画像已依据现有学习记录生成。'],
      concerns: ['当前影响最大的模型指标为“考核平均成绩”，主要增加风险。'],
      recommendedActions: ['优先完成“线性代数”相关的学习计划任务。'],
    })
  })
})
