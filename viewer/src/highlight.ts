// A small Scala tokenizer for the source preview, coloured like IntelliJ (Light / Dark themes).
// It knows keywords, literals, comments, annotations and the names that `def` declares; no type analysis.

export type TokenKind = 'kw' | 'str' | 'esc' | 'num' | 'com' | 'doc' | 'ann' | 'def' | 'interp'

export interface Token {
  text: string
  kind?: TokenKind
}

const KEYWORDS = new Set(
  (
    'abstract case catch class def do else enum extends false final finally for forSome given if implicit import lazy ' +
    'match new null object override package private protected return sealed super then this throw trait true try type ' +
    'using val var while with yield'
  ).split(' '),
)

const IDENT = /[A-Za-z_$][\w$]*/y
const NUMBER = /(?:0[xX][\da-fA-F_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)[lLfFdD]?/y
const CHAR = /'(?:\\.|[^'\\\n])'/y
const OPERATOR = /[!#%&*+\-/:<=>?@\\^|~]+/y
const OPERATOR_CHAR = /[!#%&*+\-/:<=>?@\\^|~]/

/** Files the tokenizer understands; anything else is shown as plain text. */
export function canHighlight(file: string): boolean {
  return /\.(scala|sc|sbt|java)$/.test(file)
}

/** Tokens of each line, in order. Comments and strings may span lines. */
export function highlightLines(lines: string[]): Token[][] {
  const out: Token[][] = [[]]
  for (const t of tokenize(lines.join('\n'))) {
    t.text.split('\n').forEach((part, i) => {
      if (i > 0) out.push([])
      if (part) out[out.length - 1].push({ text: part, kind: t.kind })
    })
  }
  return out
}

function tokenize(src: string): Token[] {
  const tokens: Token[] = []
  let plain = ''
  let expectDef = false
  const push = (text: string, kind?: TokenKind) => {
    if (!kind) return void (plain += text)
    if (plain) tokens.push({ text: plain })
    plain = ''
    tokens.push({ text, kind })
  }
  const match = (re: RegExp, at: number) => {
    re.lastIndex = at
    return re.exec(src)?.[0]
  }

  let i = 0
  while (i < src.length) {
    const c = src[i]
    const next = src[i + 1]

    if (c === '/' && next === '/') {
      const end = src.indexOf('\n', i)
      const text = src.slice(i, end < 0 ? src.length : end)
      push(text, 'com')
      i += text.length
      continue
    }
    if (c === '/' && next === '*') {
      const end = src.indexOf('*/', i + 2)
      const text = src.slice(i, end < 0 ? src.length : end + 2)
      push(text, text.startsWith('/**') && text !== '/**/' ? 'doc' : 'com')
      i += text.length
      continue
    }
    if (c === '"') {
      i = readString(src, i, false, push)
      continue
    }
    if (c === "'") {
      const ch = match(CHAR, i)
      if (ch) {
        push(ch, 'str')
        i += ch.length
        continue
      }
    }
    if (c === '@' && /[A-Za-z_]/.test(next ?? '')) {
      const name = match(IDENT, i + 1)!
      push(`@${name}`, 'ann')
      i += name.length + 1
      continue
    }
    if (/\d/.test(c) && !/[\w$]/.test(src[i - 1] ?? '')) {
      const num = match(NUMBER, i)!
      push(num, 'num')
      i += num.length
      continue
    }
    if (/[A-Za-z_$]/.test(c)) {
      const word = match(IDENT, i)!
      if (src[i + word.length] === '"') {
        // An interpolated string: s"…", f"…", raw"…" or any custom interpolator.
        i = readString(src, i + word.length, true, push, word)
        continue
      }
      if (expectDef) {
        push(word, 'def')
        expectDef = false
      } else if (KEYWORDS.has(word)) {
        push(word, 'kw')
        expectDef = word === 'def'
      } else push(word)
      i += word.length
      continue
    }
    if (c === '`') {
      const end = src.indexOf('`', i + 1)
      const text = src.slice(i, end < 0 || src.slice(i, end).includes('\n') ? i + 1 : end + 1)
      push(text, expectDef ? 'def' : undefined)
      expectDef = false
      i += text.length
      continue
    }
    if (expectDef && OPERATOR_CHAR.test(c)) {
      // def +(that: …), def ===(…)
      const op = match(OPERATOR, i)!
      push(op, 'def')
      expectDef = false
      i += op.length
      continue
    }
    if (!/\s/.test(c)) expectDef = false
    push(c)
    i++
  }
  if (plain) tokens.push({ text: plain })
  return tokens
}

/** Reads a string literal starting at the quote at `i`; returns the index after it. */
function readString(src: string, i: number, interpolated: boolean, push: (text: string, kind?: TokenKind) => void, prefix = ''): number {
  const triple = src.startsWith('"""', i)
  const quote = triple ? '"""' : '"'
  // Raw strings (triple quotes, raw"…") have no escapes.
  const escapes = !triple && prefix !== 'raw'
  let text = prefix + quote
  i += quote.length
  const flush = (kind: TokenKind = 'str') => {
    if (text) push(text, kind)
    text = ''
  }
  while (i < src.length) {
    if (src.startsWith(quote, i) && !(triple && src[i + 3] === '"')) {
      text += quote
      i += quote.length
      break
    }
    const c = src[i]
    if (!triple && c === '\n') break
    if (escapes && c === '\\' && i + 1 < src.length) {
      flush()
      const len = src[i + 1] === 'u' ? 6 : 2
      push(src.slice(i, i + len), 'esc')
      i += len
      continue
    }
    if (interpolated && c === '$') {
      if (src[i + 1] === '$') {
        flush()
        push('$$', 'esc')
        i += 2
        continue
      }
      if (src[i + 1] === '{') {
        let depth = 0
        let j = i + 1
        for (; j < src.length; j++) {
          if (src[j] === '{') depth++
          else if (src[j] === '}' && --depth === 0) break
        }
        flush()
        push(src.slice(i, j + 1), 'interp')
        i = j + 1
        continue
      }
      IDENT.lastIndex = i + 1
      const name = IDENT.exec(src)?.[0]
      if (name) {
        flush()
        push(`$${name}`, 'interp')
        i += name.length + 1
        continue
      }
    }
    text += c
    i++
  }
  flush()
  return i
}
