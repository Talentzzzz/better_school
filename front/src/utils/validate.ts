export function isPhone(v: string): boolean {
  return /^1\d{10}$/.test(v)
}

export function isCode(v: string): boolean {
  return /^\d{6}$/.test(v)
}
