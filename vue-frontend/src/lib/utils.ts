/**
 * Small local class-name helper.
 *
 * The shadcn-vue examples normally generate this helper together with the
 * component registry files. Keeping the helper local means the app does not
 * need to contact the shadcn registry during development or build.
 */
export function cn(...values: unknown[]): string {
  const result: string[] = []

  const append = (value: unknown) => {
    if (!value) return

    if (typeof value === 'string' || typeof value === 'number') {
      result.push(String(value))
      return
    }

    if (Array.isArray(value)) {
      value.forEach(append)
      return
    }

    if (typeof value === 'object') {
      Object.entries(value as Record<string, unknown>).forEach(([name, enabled]) => {
        if (enabled) result.push(name)
      })
    }
  }

  values.forEach(append)
  return result.join(' ')
}
