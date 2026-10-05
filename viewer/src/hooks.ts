import { useEffect, useState } from 'react'

export type Async<T> = { status: 'loading' } | { status: 'error'; error: string } | { status: 'ready'; value: T }

/** Runs `load` whenever `key` changes; stale results are dropped. */
export function useAsync<T>(key: string, load: () => Promise<T>): Async<T> {
  const [state, setState] = useState<{ key: string; value: Async<T> }>({ key, value: { status: 'loading' } })
  useEffect(() => {
    let live = true
    setState((s) => (s.key === key ? s : { key, value: { status: 'loading' } }))
    load().then(
      (value) => live && setState({ key, value: { status: 'ready', value } }),
      (e: unknown) => live && setState({ key, value: { status: 'error', error: e instanceof Error ? e.message : String(e) } }),
    )
    return () => {
      live = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  return state.key === key ? state.value : { status: 'loading' }
}
