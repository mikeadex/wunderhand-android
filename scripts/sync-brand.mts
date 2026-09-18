/* The app's icons, drawn from chairtime's own brand code.
 *
 * Run it through the wrapper, which finds chairtime and its packages:
 *   scripts/sync-brand.sh            (or CHAIRTIME=../chairtime-m5 scripts/sync-brand.sh)
 *
 * The Android half of ../wunderhand/scripts/sync-brand.mts. Three things come
 * across, all as vectors, so nothing here is a bitmap that can go soft:
 *
 *  1. The icon set. The dashboard draws with Lucide at a 1.6 stroke, so the
 *     same artwork is written into :design as VectorDrawables and used
 *     through `WHIcon`. Read from the lucide-react the website builds with,
 *     so the phone cannot end up a version behind the web. A VectorDrawable
 *     knows only paths, so Lucide's circles, rects and lines are rewritten
 *     as the paths they are.
 *  2. The mark on its own (`ic_mark`), for the lockup on the sign-in screen.
 *  3. The launcher icon, as an adaptive icon: the shears from
 *     `lib/brand/mark.ts` over the red plate the logo handoff sets out, plus
 *     the one-colour layer Android 13's themed icons ask for. And the 512px
 *     PNG the Play listing wants, which is the only bitmap.
 *
 * Add a name to ICONS when a screen needs an icon the app does not have yet,
 * run this, then use it as `WHIcon(WHIcons.Thing)`. Keep the list the same as
 * the iOS script's: an icon one app has, the other will want.
 */
import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs'

const CHAIRTIME = process.env.CHAIRTIME_ABS
const APP = process.env.APP_ABS
if (!CHAIRTIME || !APP) throw new Error('run this through scripts/sync-brand.sh')

const { MARK_PATHS, markGroup } = await import(`${CHAIRTIME}/lib/brand/mark.ts`) as {
  MARK_PATHS: { full: readonly string[]; small: readonly string[] }
  markGroup: (cut: 'full' | 'small', color: string, attrs?: string) => string
}
const sharp = (await import(`${CHAIRTIME}/node_modules/sharp/dist/index.cjs`)).default

/** Every icon the app draws. The names are Lucide's own. */
const ICONS = ['calendar-days', 'users', 'scissors', 'banknote', 'store', 'x', 'check',
  'chevron-right', 'chevron-left', 'chevron-up', 'chevron-down', 'chevrons-up-down', 'plus',
  'trash-2', 'pencil', 'search', 'lock', 'menu', 'repeat', 'share', 'user-plus',
  'arrow-up-right', 'circle-x']

const LUCIDE = `${CHAIRTIME}/node_modules/lucide-react/dist/esm/icons`
const DRAWABLES = `${APP}/design/src/main/res/drawable`
const APP_RES = `${APP}/app/src/main/res`

const n = (v: string | undefined, fallback = 0) => (v === undefined ? fallback : Number(v))
const f = (v: number) => Number(v.toFixed(3))

/** One Lucide shape as path data — all a VectorDrawable can draw. */
function asPath(tag: string, a: Record<string, string>): string {
  switch (tag) {
    case 'path': return a.d
    case 'line': return `M${a.x1} ${a.y1}L${a.x2} ${a.y2}`
    case 'polyline': return 'M' + a.points.trim().split(/\s+/).join('L').replace(/,/g, ' ')
    case 'polygon': return 'M' + a.points.trim().split(/\s+/).join('L').replace(/,/g, ' ') + 'Z'
    case 'circle': return ellipse(n(a.cx), n(a.cy), n(a.r), n(a.r))
    case 'ellipse': return ellipse(n(a.cx), n(a.cy), n(a.rx), n(a.ry))
    case 'rect': {
      const x = n(a.x), y = n(a.y), w = n(a.width), h = n(a.height)
      const rx = Math.min(n(a.rx, n(a.ry)), w / 2), ry = Math.min(n(a.ry, n(a.rx)), h / 2)
      if (rx === 0 && ry === 0) return `M${x} ${y}h${w}v${h}h${-w}Z`
      return `M${f(x + rx)} ${y}h${f(w - 2 * rx)}a${rx} ${ry} 0 0 1 ${rx} ${ry}v${f(h - 2 * ry)}`
        + `a${rx} ${ry} 0 0 1 ${-rx} ${ry}h${f(-(w - 2 * rx))}a${rx} ${ry} 0 0 1 ${-rx} ${-ry}`
        + `v${f(-(h - 2 * ry))}a${rx} ${ry} 0 0 1 ${rx} ${-ry}Z`
    }
    default: throw new Error(`a shape this script cannot turn into a path: <${tag}>`)
  }
}

function ellipse(cx: number, cy: number, rx: number, ry: number): string {
  return `M${f(cx - rx)} ${cy}a${rx} ${ry} 0 1 0 ${f(rx * 2)} 0a${rx} ${ry} 0 1 0 ${f(-rx * 2)} 0Z`
}

/** An icon's shapes, out of the module lucide-react ships. */
function paths(name: string): string[] {
  const src = readFileSync(`${LUCIDE}/${name}.js`, 'utf8')
  const body = src.slice(src.indexOf('['), src.lastIndexOf(']') + 1)
  const out: string[] = []
  for (const m of body.matchAll(/\["(\w+)",\s*\{([^}]*)\}\]/g)) {
    const attrs = Object.fromEntries([...m[2].matchAll(/(\w[\w-]*):\s*"([^"]*)"/g)].map(([, k, v]) => [k, v]))
    out.push(asPath(m[1], attrs))
  }
  if (out.length === 0) throw new Error(`no shapes read from ${name}`)
  return out
}

const HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Written by scripts/sync-brand.sh. Change the script, not this file. -->\n'

mkdirSync(DRAWABLES, { recursive: true })
for (const name of ICONS) {
  if (!existsSync(`${LUCIDE}/${name}.js`)) throw new Error(`lucide has no icon called ${name}`)
  // Black, and tinted where it is drawn: the colour is the screen's to choose.
  const xml = HEADER
    + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    + '    android:width="24dp" android:height="24dp"\n'
    + '    android:viewportWidth="24" android:viewportHeight="24">\n'
    + paths(name).map((d) =>
      `    <path android:pathData="${d}"\n        android:strokeColor="#FF000000" android:strokeWidth="1.6"\n`
      + '        android:strokeLineCap="round" android:strokeLineJoin="round" />\n').join('')
    + '</vector>\n'
  writeFileSync(`${DRAWABLES}/ic_${name.replace(/-/g, '_')}.xml`, xml)
}
console.log(`${ICONS.length} icons → design/src/main/res/drawable`)

/** The mark's own paths, filled even-odd so the rings keep their holes. */
const markPaths = (color: string, indent: string) => MARK_PATHS.full.map((d) =>
  `${indent}<path android:pathData="${d}" android:fillColor="${color}" android:fillType="evenOdd" />\n`).join('')

writeFileSync(`${DRAWABLES}/ic_mark.xml`, HEADER
  + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
  + '    android:width="100dp" android:height="100dp"\n'
  + '    android:viewportWidth="100" android:viewportHeight="100">\n'
  + markPaths('#FF000000', '    ') + '</vector>\n')
console.log('the mark → design/src/main/res/drawable/ic_mark.xml')

/* The launcher icon. An adaptive icon is a 108dp square of which the system
 * shows a 72dp window, and only the middle 66dp circle is promised on every
 * launcher's mask. The mark's furthest point is 56 of its 100 units from its
 * centre, so at 54dp it stays inside that circle with room to spare. */
const GROUND = '#FFF6F5F4'
const MARK_DP = 54, MARK_AT = (108 - MARK_DP) / 2
const layer = (color: string) => HEADER
  + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
  + '    android:width="108dp" android:height="108dp"\n'
  + '    android:viewportWidth="108" android:viewportHeight="108">\n'
  + `    <group android:translateX="${MARK_AT}" android:translateY="${MARK_AT}" android:scaleX="${MARK_DP / 100}" android:scaleY="${MARK_DP / 100}">\n`
  + markPaths(color, '        ') + '    </group>\n</vector>\n'

const stop = (offset: number, color: string) => `                <item android:offset="${offset}" android:color="${color}" />\n`
// The signature red plate, as appIcon() in chairtime's scripts/brand-icons.ts draws it.
const background = HEADER
  + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
  + '    xmlns:aapt="http://schemas.android.com/aapt"\n'
  + '    android:width="108dp" android:height="108dp"\n'
  + '    android:viewportWidth="108" android:viewportHeight="108">\n'
  + '    <path android:pathData="M0 0h108v108h-108Z">\n        <aapt:attr name="android:fillColor">\n'
  + '            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">\n'
  + stop(0, '#FFC8502F') + stop(0.46, '#FFB03A22') + stop(1, '#FF6D2716')
  + '            </gradient>\n        </aapt:attr>\n    </path>\n'
  + '    <path android:pathData="M0 0h108v108h-108Z">\n        <aapt:attr name="android:fillColor">\n'
  + `            <gradient android:type="radial" android:centerX="${f(108 * 0.2)}" android:centerY="${f(108 * 0.14)}" android:gradientRadius="${f(108 * 0.85)}">\n`
  + stop(0, '#8CE06A47') + stop(1, '#00E06A47')
  + '            </gradient>\n        </aapt:attr>\n    </path>\n</vector>\n'

mkdirSync(`${APP_RES}/drawable`, { recursive: true })
mkdirSync(`${APP_RES}/mipmap-anydpi-v26`, { recursive: true })
writeFileSync(`${APP_RES}/drawable/ic_launcher_foreground.xml`, layer(GROUND))
writeFileSync(`${APP_RES}/drawable/ic_launcher_monochrome.xml`, layer('#FF000000'))
writeFileSync(`${APP_RES}/drawable/ic_launcher_background.xml`, background)
const adaptive = HEADER
  + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
  + '    <background android:drawable="@drawable/ic_launcher_background" />\n'
  + '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
  + '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
  + '</adaptive-icon>\n'
writeFileSync(`${APP_RES}/mipmap-anydpi-v26/ic_launcher.xml`, adaptive)
writeFileSync(`${APP_RES}/mipmap-anydpi-v26/ic_launcher_round.xml`, adaptive)
console.log('launcher icon → app/src/main/res (adaptive, with the themed layer)')

/* Play's listing icon: 512px, full bleed — Play rounds the corners itself. */
{
  const size = 512, markPx = size * 0.6, at = ((size - markPx) / 2).toFixed(2)
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">`
    + '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#c8502f"/><stop offset=".46" stop-color="#b03a22"/><stop offset="1" stop-color="#6d2716"/></linearGradient>'
    + '<radialGradient id="glow" cx="0.2" cy="0.14" r="0.85"><stop offset="0" stop-color="#e06a47" stop-opacity=".55"/><stop offset="1" stop-color="#e06a47" stop-opacity="0"/></radialGradient></defs>'
    + `<rect width="${size}" height="${size}" fill="url(#g)"/><rect width="${size}" height="${size}" fill="url(#glow)"/>`
    + markGroup('full', '#f6f5f4', `transform="translate(${at},${at}) scale(${(markPx / 100).toFixed(4)})"`)
    + '</svg>'
  mkdirSync(`${APP}/play`, { recursive: true })
  const png = await sharp(Buffer.from(svg), { density: 72 * 4 }).resize(size, size).png().toBuffer()
  writeFileSync(`${APP}/play/icon-512.png`, png)
  console.log(`play/icon-512.png → ${(png.length / 1024).toFixed(0)}KB`)
}
