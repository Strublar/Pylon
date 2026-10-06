// Loads what the canvas needs for a view state and turns it into boxes and arrows.
import type { Callee, Caller, NodeDetails, SymbolNode } from './api'
import { api } from './api'
import type { DownStep, ViewState } from './state'

export interface StepModel {
  index: number
  step: DownStep
  called: NodeDetails
  chosen: NodeDetails | null
  /** The implementation walked through: the choice, the only implementation, or the called method itself. */
  effective: SymbolNode
  /** True when the step is abstract with several implementations and nothing is chosen yet. */
  needsChoice: boolean
}

export type DownModel = {
  mode: 'down'
  steps: StepModel[]
  /** Calls made by the last step (empty while it needs a choice or is a type). */
  callees: Callee[]
}

export interface UpModel {
  mode: 'up'
  chain: NodeDetails[]
  /** For each chain element after the first: how it calls the previous one. */
  links: (Caller | null)[]
  callers: Caller[]
}

export type CanvasModel = DownModel | UpModel

export function isType(n: SymbolNode): boolean {
  return n.kind === 'trait' || n.kind === 'class' || n.kind === 'object'
}

export async function loadDown(state: ViewState): Promise<DownModel> {
  const steps: StepModel[] = await Promise.all(
    state.down.map(async (step, index) => {
      const [called, chosen] = await Promise.all([api.node(step.sym), step.choice ? api.node(step.choice) : null])
      const impls = called.implementations
      const sole = !chosen && called.node.abstract && impls.length === 1 ? impls[0] : null
      const effective = chosen?.node ?? sole ?? called.node
      const needsChoice = !chosen && !sole && called.node.abstract && impls.length > 1
      return { index, step, called, chosen, effective, needsChoice }
    }),
  )
  const last = steps[steps.length - 1]
  const callees =
    last && !last.needsChoice && !isType(last.effective) ? await api.callees(last.effective.symbol, state.showExternal) : []
  return { mode: 'down', steps, callees }
}

export async function loadUp(state: ViewState): Promise<UpModel> {
  const chain = await Promise.all(state.up.map((s) => api.node(s)))
  const callersPerElement = await Promise.all(state.up.map((s) => api.callers(s)))
  const links = state.up.map((sym, i) =>
    i === 0 ? null : (callersPerElement[i - 1].find((c) => c.caller.symbol === sym) ?? null),
  )
  return { mode: 'up', chain, links, callers: callersPerElement[callersPerElement.length - 1] ?? [] }
}
