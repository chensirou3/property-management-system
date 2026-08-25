import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MoneyCell from './MoneyCell.vue'
import StatusTag from './StatusTag.vue'
import QueryPanel from './QueryPanel.vue'

describe('shared page primitives', () => {
  it('formats valid money and keeps empty values explicit', () => {
    expect(mount(MoneyCell, { props: { value: '1234.5' } }).text()).toContain('1,234.50')
    expect(mount(MoneyCell, { props: { value: null } }).text()).toBe('—')
  })

  it('normalizes backend task and business statuses', () => {
    expect(mount(StatusTag, { props: { value: 'SUCCEEDED' } }).text()).toBe('成功')
    expect(mount(StatusTag, { props: { value: 'PARTIAL_FAILED' } }).text()).toBe('部分失败')
  })

  it('emits query and reset from a consistent action area', async () => {
    const wrapper = mount(QueryPanel)
    const buttons = wrapper.findAll('button')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    expect(wrapper.emitted('query')).toHaveLength(1)
    expect(wrapper.emitted('reset')).toHaveLength(1)
  })
})
