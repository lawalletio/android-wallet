// Converts apps/web/lib/client/i18n/{en,es} into Android strings.xml (same keys, same copy).
import { writeFileSync, mkdirSync } from 'fs'
const root = process.argv[2]
const out = process.argv[3]
const esc = (s: string, formatted: boolean) => {
  let r = s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"').replace(/\n/g, '\\n')
  if (formatted) r = r.replace(/%(?!\d+\$s)/g, '%%')
  if (/^[@?]/.test(r)) r = '\\' + r
  return r
}
function flatten(obj: any, prefix: string, acc: [string, string, boolean][]) {
  for (const [k, v] of Object.entries(obj)) {
    const key = (prefix ? prefix + '_' : '') + k.replace(/[^A-Za-z0-9_]/g, '_')
    if (typeof v === 'string') acc.push([key, v, false])
    else if (typeof v === 'function') {
      const args = Array.from({ length: Math.max(v.length, 1) }, (_, i) => `%${i + 1}$s`)
      let s: any
      try { s = (v as any)(...args) } catch { continue }
      if (typeof s === 'string') acc.push([key, s, true])
    } else if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, acc)
  }
  return acc
}
for (const [loc, dir] of [['en', 'values'], ['es', 'values-es']] as const) {
  const mod = await import(`${root}/i18n-${loc}.mjs`)
  const dict = mod.default ?? mod
  const rows = flatten(dict, '', [])
  const xml = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- GENERATED from lawallet-nwc apps/web/lib/client/i18n/' + loc + ' by scripts/i18n2xml.ts — do not edit by hand. -->', '<resources>']
  for (const [k, v, f] of rows) xml.push(`    <string name="${k}"${f ? '' : (v.includes('%') ? ' formatted="false"' : '')}>${esc(v, f)}</string>`)
  xml.push('</resources>', '')
  mkdirSync(`${out}/${dir}`, { recursive: true })
  writeFileSync(`${out}/${dir}/strings_web.xml`, xml.join('\n'))
  console.log(loc, rows.length)
}
