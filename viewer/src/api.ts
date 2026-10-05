// Typed client for the Pylon JSON API (modules/server Api.scala).

export interface SymbolNode {
  symbol: string
  kind: 'trait' | 'class' | 'object' | 'method' | 'constructor' | 'val' | 'endpoint' | 'other'
  name: string
  display: string
  ownerDisplay: string
  owner: string
  signature: string
  service: string | null
  file: string | null
  line: number | null
  abstract: boolean
  external: boolean
}

export interface Site {
  file: string
  line: number
}

export interface Callee {
  target: SymbolNode
  sites: Site[]
  synthetic: boolean
  fork: boolean
  candidates: SymbolNode[]
  sole: SymbolNode | null
}

export interface Caller {
  caller: SymbolNode
  via: SymbolNode
  sites: Site[]
}

export interface NodeDetails {
  node: SymbolNode
  implementations: SymbolNode[]
  overrides: SymbolNode[]
  subtypes: SymbolNode[]
  members: SymbolNode[]
}

export interface PathStep {
  node: SymbolNode
  via: SymbolNode | null
}

export interface Source {
  file: string
  service: string
  startLine: number
  focusLine: number
  endLine: number
  lines: string[]
}

const cache = new Map<string, Promise<unknown>>()

async function get<T>(path: string, params: Record<string, string>): Promise<T> {
  const url = `${path}?${new URLSearchParams(params)}`
  let p = cache.get(url)
  if (!p) {
    p = fetch(url).then(async (r) => {
      if (!r.ok) {
        cache.delete(url)
        const body = await r.json().catch(() => ({ error: r.statusText }))
        throw new Error(body.error ?? r.statusText)
      }
      return r.json()
    })
    cache.set(url, p)
  }
  return p as Promise<T>
}

export const api = {
  search: (q: string) => get<SymbolNode[]>('api/search', { q, limit: '12' }),
  endpoints: () => get<SymbolNode[]>('api/endpoints', {}),
  node: (sym: string) => get<NodeDetails>('api/node', { sym }),
  callees: (sym: string, external: boolean) => get<Callee[]>('api/callees', { sym, external: external ? '1' : '0' }),
  callers: (sym: string) => get<Caller[]>('api/callers', { sym }),
  paths: (sym: string) => get<PathStep[][]>('api/paths', { sym }),
  source: (sym: string) => get<Source>('api/source', { sym }),
}
