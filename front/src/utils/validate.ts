export function isPhone(v: string): boolean {
  return /^1\d{10}$/.test(v)
}

export function isCode(v: string): boolean {
  return /^\d{6}$/.test(v)
}

export function isUuid(v: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
}
