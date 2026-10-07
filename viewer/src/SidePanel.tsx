// The right-hand panel: resizable by dragging its left edge, and able to widen for a while over the canvas
// (e.g. while the pointer is on the source, so that long lines are visible), sliding back afterwards.
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'

const MIN_WIDTH = 300
const DEFAULT_WIDTH = 420
/** Canvas left visible beside the panel, however wide it gets. */
const CANVAS_MARGIN = 120
const STORAGE_KEY = 'pylon.panelWidth'

/** Widens the panel until `el` no longer scrolls sideways (after a short delay); `null` puts it back. */
type Fit = (el: HTMLElement | null) => void

const FitContext = createContext<Fit>(() => {})
export const useFitPanel = () => useContext(FitContext)

function readWidth(): number {
  try {
    const w = Number(window.localStorage.getItem(STORAGE_KEY))
    return w >= MIN_WIDTH ? w : DEFAULT_WIDTH
  } catch {
    return DEFAULT_WIDTH
  }
}

function saveWidth(w: number): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, String(Math.round(w)))
  } catch {
    /* the width is only a convenience */
  }
}

export function SidePanel({ children }: { children: ReactNode }) {
  const [width, setWidth] = useState(readWidth)
  const [extra, setExtra] = useState(0)
  const [resizing, setResizing] = useState(false)
  const dock = useRef<HTMLDivElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const timer = useRef<number | undefined>(undefined)

  const maxWidth = () => Math.max(MIN_WIDTH, (dock.current?.parentElement?.clientWidth ?? window.innerWidth) - CANVAS_MARGIN)

  const fit: Fit = useCallback(
    (el) => {
      window.clearTimeout(timer.current)
      timer.current = window.setTimeout(
        () => {
          if (!el || !panel.current) return setExtra(0)
          const overflow = el.scrollWidth - el.clientWidth
          if (overflow > 0) setExtra(Math.max(0, panel.current.offsetWidth + overflow + 2 - width))
        },
        el ? 160 : 100,
      )
    },
    [width],
  )
  useEffect(() => () => window.clearTimeout(timer.current), [])

  const startResize = (e: React.PointerEvent) => {
    e.preventDefault()
    const startX = e.clientX
    const startW = width
    let w = startW
    setExtra(0)
    setResizing(true)
    const move = (ev: PointerEvent) => {
      w = Math.min(maxWidth(), Math.max(MIN_WIDTH, startW + startX - ev.clientX))
      setWidth(w)
    }
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      setResizing(false)
      saveWidth(w)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }

  const shown = extra > 0 ? Math.min(width + extra, maxWidth()) : width
  return (
    <FitContext.Provider value={fit}>
      <div className="dock" ref={dock} style={{ width }}>
        <div
          ref={panel}
          className={`dock-panel ${shown > width ? 'dock-expanded' : ''} ${resizing ? 'dock-resizing' : ''}`}
          style={{ width: shown }}
        >
          {children}
          <div
            className="dock-grip"
            role="separator"
            aria-orientation="vertical"
            title="Drag to resize the panel (double-click to reset)"
            onPointerDown={startResize}
            onDoubleClick={() => {
              setWidth(DEFAULT_WIDTH)
              saveWidth(DEFAULT_WIDTH)
            }}
          />
        </div>
      </div>
    </FitContext.Provider>
  )
}
