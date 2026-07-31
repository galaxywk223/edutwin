export function percent(value: number, digits = 1) {
  return `${(value * 100).toFixed(digits)}%`
}

export function shortId(value: string, length = 8) {
  return value.length <= length ? value : value.slice(0, length)
}

export function dateTime(value: string | null | undefined) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

export function number(value: number, digits = 3) {
  return new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: digits,
    minimumFractionDigits: 0,
  }).format(value)
}
