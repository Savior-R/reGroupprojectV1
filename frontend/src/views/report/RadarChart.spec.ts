import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RadarChart from './RadarChart.vue'

describe('RadarChart', () => {
  it('plots every axis and labels a missing dimension as 未提供', () => {
    const wrapper = mount(RadarChart, {
      props: {
        axes: ['内容', '表达', '互动', '板书', '时间'],
        values: [82, 72.5, 80, null, 90],
      },
    })

    const svg = wrapper.find('svg')
    expect(svg.exists()).toBe(true)
    expect(svg.attributes('aria-label')).toBe(
      '维度雷达图：内容 82.0，表达 72.5，互动 80.0，板书 未提供，时间 90.0',
    )
    expect(wrapper.findAll('circle')).toHaveLength(5)
    expect(wrapper.text()).toContain('板书')
    expect(wrapper.find('.radar__missing').text()).toBe('未提供')
    expect(wrapper.text()).toContain('—')
  })

  it('renders an explicit empty state when every dimension is missing', () => {
    const wrapper = mount(RadarChart, {
      props: { axes: ['内容', '表达', '时间'], values: [null, null, null] },
    })

    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无可绘制的维度得分')
  })

  it('requires at least three axes to draw a polygon', () => {
    const wrapper = mount(RadarChart, {
      props: { axes: ['内容', '表达'], values: [80, 70] },
    })

    expect(wrapper.find('svg').exists()).toBe(false)
  })

  it('clamps out-of-range values into the radar area', () => {
    const wrapper = mount(RadarChart, {
      props: { axes: ['内容', '表达', '时间'], values: [130, -20, 50] },
    })

    expect(wrapper.find('svg').exists()).toBe(true)
    expect(wrapper.text()).toContain('130.0')
    expect(wrapper.text()).toContain('-20.0')
  })
})
