import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { sensor } from '../test/fakeApi'
import { SensorGrid } from './SensorGrid'

const NOW = Date.now()

function renderGrid(sensors = [sensor(0, 0, 10.0), sensor(1, 0, 11.0), sensor(1, 2, 21.5)], selected = null) {
  const onSelect = vi.fn()
  render(<SensorGrid sensors={sensors} maxTemperatureC={20} selected={selected} onSelect={onSelect} now={NOW} />)
  return onSelect
}

/** The cell texts, row by row, as they appear on screen. */
function gridText() {
  const table = screen.getByRole('table', { name: /Latest temperature/ })
  return within(table)
    .getAllByRole('row')
    .map((row) => within(row).queryAllByRole('columnheader').concat(within(row).queryAllByRole('rowheader'), within(row).queryAllByRole('button')).map((c) => c.textContent))
}

describe('the sensor grid', () => {
  it('puts cables across and depths down, with the top of the cable first', () => {
    renderGrid()

    expect(gridText()).toEqual([
      // The corner holds a word for screen readers only.
      ['Depth', 'Cable 1', 'Cable 2'],
      ['Top', '10.0°', '11.0°'],
      ['Depth 2', '—', '—'],
      ['Depth 3', '—', '▲ 21.5°'],
    ])
  })

  it('marks a reading above the limit with an icon and says so in words', () => {
    renderGrid()

    const hot = screen.getByRole('button', { name: /Cable 2, depth 3: 21.5 °C, above the temperature limit/ })
    expect(hot.textContent).toContain('▲')
    expect(hot.className).toContain('cell-hot')
  })

  it('shows a position with no recent reading as a gap, not a number', () => {
    renderGrid()

    expect(screen.getByRole('button', { name: 'Cable 1, depth 3: no reading in the last seven days' }).textContent).toBe('—')
  })

  it('selects a sensor when its cell is clicked, and marks the selected one', async () => {
    const onSelect = renderGrid(undefined, { cable: 1, depth: 0 } as never)

    await userEvent.setup().click(screen.getByRole('button', { name: /Cable 1, depth 1 \(top\): 10.0/ }))

    expect(onSelect).toHaveBeenCalledWith({ cable: 0, depth: 0 })
    expect(screen.getByRole('button', { name: /Cable 2, depth 1 \(top\): 11.0/ }).getAttribute('aria-pressed')).toBe('true')
  })

  it('says so when nothing has reported in the last week', () => {
    renderGrid([])

    expect(screen.getByText('No readings in the last seven days.')).toBeTruthy()
  })
})
