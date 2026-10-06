// View state, mirrored into the URL hash so that back/forward and shared links work.

/** One step of a downward walk: `sym` is the method that was called (possibly abstract), `choice` the implementation picked. */
export interface DownStep {
  sym: string
  choice?: string
}

export type Mode = 'down' | 'up'

/** `packages`: classes drawn inside their package boxes; `chain`: one box per step, in columns. */
export type View = 'packages' | 'chain'

export interface ViewState {
  mode: Mode
  /** Root first. The last step is the one whose calls are shown. */
  down: DownStep[]
  /** `up[0]` is the method being investigated; `up[i + 1]` is a caller of `up[i]`. */
  up: string[]
  /** Node shown in the details panel. */
  selected?: string
  showExternal: boolean
  /** Absent means `packages`. */
  view?: View
}

export const emptyState: ViewState = { mode: 'down', down: [], up: [], showExternal: false }

export function startAt(sym: string, mode: Mode = 'down', view?: View): ViewState {
  return { mode, down: [{ sym }], up: [sym], selected: sym, showExternal: false, view }
}

export function readState(): ViewState {
  const hash = window.location.hash.replace(/^#/, '')
  if (hash.startsWith('s=')) {
    try {
      return { ...emptyState, ...JSON.parse(decodeURIComponent(hash.slice(2))) }
    } catch {
      /* fall through to the query string */
    }
  }
  const params = new URLSearchParams(window.location.search)
  const sym = params.get('sym')
  const view = params.get('view') === 'chain' ? 'chain' : undefined
  return sym ? startAt(sym, params.get('mode') === 'up' ? 'up' : 'down', view) : { ...emptyState, view }
}

export function writeState(state: ViewState, replace = false): void {
  const url = `${window.location.pathname}${window.location.search}#s=${encodeURIComponent(JSON.stringify(state))}`
  if (replace) window.history.replaceState(null, '', url)
  else window.history.pushState(null, '', url)
}

/** The implementation actually walked through at a step. */
export function effective(step: DownStep): string {
  return step.choice ?? step.sym
}
