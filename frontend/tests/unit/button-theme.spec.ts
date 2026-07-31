import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { mount } from '@vue/test-utils'
import { ElButton } from 'element-plus'
import { Send } from '@lucide/vue'
import { afterEach, describe, expect, it } from 'vitest'

const elementButtonCss = readFileSync(
  resolve(process.cwd(), 'node_modules/element-plus/theme-chalk/el-button.css'),
  'utf8',
)
const projectCss = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

function hexToRgb(hex: string) {
  const value = Number.parseInt(hex.slice(1), 16)
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255]
}

function luminance(hex: string) {
  const channels = hexToRgb(hex).map((channel) => {
    const value = channel / 255
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  })
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
}

function contrast(foreground: string, background: string) {
  const light = Math.max(luminance(foreground), luminance(background))
  const dark = Math.min(luminance(foreground), luminance(background))
  return (light + 0.05) / (dark + 0.05)
}

function escapeRegex(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function rule(css: string, selector: string) {
  const match = css.match(new RegExp(`${escapeRegex(selector)}\\s*\\{([^}]*)\\}`))
  expect(match, `missing CSS rule: ${selector}`).not.toBeNull()
  return match?.[1] ?? ''
}

function property(block: string, name: string) {
  const match = block.match(new RegExp(`${escapeRegex(name)}\\s*:\\s*([^;]+)`))
  expect(match, `missing CSS property: ${name}`).not.toBeNull()
  return match?.[1].trim() ?? ''
}

describe('primary button theme', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('maps the project primary palette to the Element Plus theme contract', () => {
    const root = rule(projectCss, ':root')

    expect(property(root, '--el-color-primary')).toBe('#0f766e')
    expect(property(root, '--el-color-primary-rgb')).toBe('15, 118, 110')
    expect(property(root, '--el-color-primary-dark-2')).toBe('#115e59')
    expect([
      property(root, '--el-color-primary-light-3'),
      property(root, '--el-color-primary-light-5'),
      property(root, '--el-color-primary-light-7'),
      property(root, '--el-color-primary-light-8'),
      property(root, '--el-color-primary-light-9'),
    ]).toEqual(['#579f9a', '#87bbb7', '#b7d6d4', '#cfe4e2', '#e7f1f1'])
    expect(elementButtonCss).toContain('--el-button-bg-color:var(--el-color-primary)')
    expect(elementButtonCss).toContain('color:var(--el-button-text-color)')
  })

  it('keeps solid primary button text and icons on the intended interaction colors', () => {
    const wrapper = mount(ElButton, {
      attachTo: document.body,
      props: { type: 'primary', icon: Send },
      slots: { default: '提交并评分' },
    })
    const button = wrapper.element as HTMLButtonElement
    const solid = rule(projectCss, '.el-button--primary:not(.is-link, .is-text, .is-plain)')

    expect(property(solid, '--el-button-text-color')).toBe('#ffffff')
    expect(property(solid, '--el-button-bg-color')).toBe('#0f766e')
    expect(property(solid, '--el-button-hover-bg-color')).toBe('#115e59')
    expect(property(solid, '--el-button-active-bg-color')).toBe('#134e4a')
    expect(button.querySelector('span')?.matches('.lms-assessment-actions > span')).toBe(false)
    expect(button.querySelector('svg')?.closest('button')).toBe(button)
    expect(button.querySelector('svg')?.getAttribute('color')).toBeNull()
    expect(contrast('#ffffff', '#0f766e')).toBeGreaterThanOrEqual(5.47)
  })

  it('uses an accessible teal disabled state for text and icons', () => {
    const wrapper = mount(ElButton, {
      attachTo: document.body,
      props: { type: 'primary', icon: Send, disabled: true },
      slots: { default: '提交并评分' },
    })
    const solid = rule(projectCss, '.el-button--primary:not(.is-link, .is-text, .is-plain)')

    expect(wrapper.classes()).toContain('is-disabled')
    expect(property(solid, '--el-button-disabled-text-color')).toBe('#0f766e')
    expect(property(solid, '--el-button-disabled-bg-color')).toBe('#ccfbf1')
    expect(property(solid, '--el-button-disabled-border-color')).toBe('#99f6e4')
    expect(contrast('#0f766e', '#ccfbf1')).toBeGreaterThanOrEqual(4.85)
  })

  it('limits LMS metadata rules and the solid theme to their intended elements', () => {
    document.body.innerHTML = `
      <article class="lms-lesson-reader">
        <header>
          <div><span id="lesson-meta">课时 1</span></div>
          <button class="el-button el-button--primary"><span id="lesson-button">完成课时</span></button>
        </header>
      </article>
      <div class="lms-assessment-actions">
        <span id="assessment-note">提交后不可修改</span>
        <button class="el-button el-button--primary is-plain"><span id="plain-button">查看说明</span></button>
      </div>
    `

    expect(document.querySelector('#lesson-meta')?.matches('.lms-lesson-reader > header > div > span')).toBe(true)
    expect(document.querySelector('#lesson-button')?.matches('.lms-lesson-reader > header > div > span')).toBe(false)
    expect(document.querySelector('#assessment-note')?.matches('.lms-assessment-actions > span')).toBe(true)
    expect(document.querySelector('#plain-button')?.matches('.lms-assessment-actions > span')).toBe(false)
    expect(document.querySelector('.is-plain')?.matches('.el-button--primary:not(.is-link, .is-text, .is-plain)')).toBe(false)
  })
})
