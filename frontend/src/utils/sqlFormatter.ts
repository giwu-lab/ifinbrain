/**
 * SQL 美化器（SELECT / WITH 家族为主）。
 *
 * <p><b>设计前提：格式化绝不能改变语义。</b> 这里的 SQL 会被 SqlSafetyChecker
 * 拦下来真正执行，改错一个逗号位置就是一次静默的数据事故。所以实现刻意保守：
 * <ol>
 *   <li>先完整分词，<b>字符串字面量、引号标识符、注释一律当不可分割的原子</b>，
 *       绝不在它们内部做任何空白处理（否则 {@code 'a, b'} 会被拆成两列）；</li>
 *   <li>只重排 token 之间的空白、给主句关键字换行缩进、把关键字大写；</li>
 *   <li><b>兜底校验</b>：格式化后重新分词，与输入的 token 序列逐一比对
 *       （忽略空白、关键字统一按大写比较）。一旦不一致就<b>原样返回输入</b>，
 *       宁可「没格式化」，也绝不输出一段可能被改坏的 SQL。</li>
 * </ol>
 *
 * <p>纯函数、不依赖 DOM / Monaco，可直接单测（scripts/verify-sql-format.mjs）。
 */

export type TokenType = 'ws' | 'comment' | 'string' | 'placeholder' | 'word' | 'number' | 'punct';

export interface Token {
  type: TokenType;
  value: string;
  /** 命中的关键字（用于大写、以及兜底比对时的归一化） */
  keyword: string | null;
}

/** 换行 + 缩进的主句关键字 */
const MAJOR = new Set([
  'SELECT', 'FROM', 'WHERE', 'HAVING', 'LIMIT', 'OFFSET',
  'VALUES', 'SET', 'UNION', 'EXCEPT', 'INTERSECT', 'WITH',
  'INSERT', 'UPDATE', 'DELETE', 'MERGE',
]);

/**
 * 需要整体识别、不能从中间断开的多词短语。
 * 比对按「词数从多到少」尝试，所以 ON DUPLICATE KEY UPDATE 会先于 UNION ALL 命中。
 */
const PHRASES: string[][] = [
  ['ON', 'DUPLICATE', 'KEY', 'UPDATE'],
  ['LEFT', 'OUTER', 'JOIN'],
  ['RIGHT', 'OUTER', 'JOIN'],
  ['FULL', 'OUTER', 'JOIN'],
  ['UNION', 'ALL'],
  ['GROUP', 'BY'],
  ['ORDER', 'BY'],
  ['LEFT', 'JOIN'],
  ['RIGHT', 'JOIN'],
  ['INNER', 'JOIN'],
  ['FULL', 'JOIN'],
  ['CROSS', 'JOIN'],
  ['NATURAL', 'JOIN'],
  ['INSERT', 'INTO'],
  ['DELETE', 'FROM'],
  ['IS', 'NOT', 'NULL'],
];

/** 只大写、不换行的关键字 */
const UPPERCASE = new Set([
  'BY', 'ON', 'AND', 'OR', 'NOT', 'IN', 'IS', 'NULL', 'AS', 'CASE', 'WHEN',
  'THEN', 'ELSE', 'END', 'DISTINCT', 'ALL', 'ANY', 'EXISTS', 'BETWEEN', 'LIKE',
  'ILIKE', 'ASC', 'DESC', 'NULLS', 'FIRST', 'LAST', 'OVER', 'PARTITION',
  'RECURSIVE', 'USING', 'RETURNING', 'CONFLICT', 'DO', 'NOTHING', 'CAST',
  'INTERVAL', 'TRUE', 'FALSE', 'UNKNOWN', 'FILTER', 'WITHIN', 'GROUPING',
  'ROLLUP', 'CUBE', 'LATERAL', 'UNNEST', 'ARRAY', 'ESCAPE', 'COLLATE',
]);

/** 所有需要按关键字识别的词（含 PHRASES 里出现过的），供分词阶段判定 */
const KEYWORD = new Set<string>([
  ...MAJOR,
  ...UPPERCASE,
  ...PHRASES.flat(),
]);

/** 这些关键字后面跟空格再写左括号（IN (…)、AS (SELECT …)，而不是 IN(…)、AS(…)） */
const SPACE_BEFORE_PAREN = new Set([
  'IN', 'VALUES', 'EXISTS', 'ON', 'AND', 'OR', 'NOT', 'RETURNING', 'USING', 'BETWEEN',
  'AS', 'CASE', 'WHEN', 'ALL', 'ANY', 'DISTINCT', 'SELECT', 'FROM', 'WHERE', 'BY',
  'HAVING', 'UNION', 'INTO', 'SET', 'GROUP', 'ORDER', 'LIMIT', 'JOIN',
]);

/** 多字符运算符 / 类型转换，必须整体识别，否则 {@code >=} 会被拆成 {@code > =} */
const MULTI_OPS = ['<=', '>=', '<>', '!=', '||', '::', '->>', '->'];

const INDENT = '  ';

/**
 * 占位符：必须和字符串一样当成<b>不可分割的原子</b>。
 *
 * <p>这是本格式化器最危险的一处，务必理解：
 * 后端用正则识别占位符 ——
 * <pre>
 *   MUSTACHE  \{\{(name)\}\}(?:::(type))?      ← 唯一受支持的参数语法
 * </pre>
 * 如果在花括号两侧插入空格，{@code {{no}}} 会变成 {@code {{ no }}——
 * <b>分词器认为 token 序列没变（所以指纹兜底不会拦），但占位符正则再也匹配不上</b>，
 * 这段 SQL 就会带着字面量 {@code {{ no }} 发给数据库，直接语法错误。
 *
 * <p>所以占位符走独立分支整体透传，前后不加任何空格。
 *
 * <p>下面还有一个 {@code :name} 分支，它<b>不代表支持</b>：后端已经取消 :name 语法，
 * 命中即报错。这里仍把它当原子保留，只是为了让还留着 :name 的老 SQL 在编辑器里
 * 保持原样可读（用户能一眼看到要改哪里），而不是被格式化成 {@code : name} 之后
 * 连报错定位都变难。
 */
const MUSTACHE_RE = /\{\{[a-zA-Z_][a-zA-Z0-9_]*\}\}(?:::[a-zA-Z_][a-zA-Z0-9_]*)?/y;
const COLON_RE = /(?<!:):[a-zA-Z_][a-zA-Z0-9_]*/y;

export function tokenize(sql: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  const n = sql.length;

  while (i < n) {
    const c = sql[i];

    if (/\s/.test(c)) {
      let j = i;
      while (j < n && /\s/.test(sql[j])) j++;
      out.push({ type: 'ws', value: sql.slice(i, j), keyword: null });
      i = j;
      continue;
    }

    if (c === '-' && sql[i + 1] === '-') {
      let j = i;
      while (j < n && sql[j] !== '\n') j++;
      out.push({ type: 'comment', value: sql.slice(i, j), keyword: null });
      i = j;
      continue;
    }

    if (c === '/' && sql[i + 1] === '*') {
      let j = i + 2;
      while (j < n && !(sql[j] === '*' && sql[j + 1] === '/')) j++;
      j = Math.min(n, j + 2);
      out.push({ type: 'comment', value: sql.slice(i, j), keyword: null });
      i = j;
      continue;
    }

    // 字符串 '…'（'' 转义）/ 引号标识符 "…"（"" 转义）/ 反引号 `…`
    if (c === "'" || c === '"' || c === '`') {
      let j = i + 1;
      while (j < n) {
        if (sql[j] === c) {
          if (sql[j + 1] === c) { j += 2; continue; }
          j++;
          break;
        }
        j++;
      }
      out.push({ type: c === "'" ? 'string' : 'word', value: sql.slice(i, j), keyword: null });
      i = j;
      continue;
    }

    // 占位符 {{name}}::type —— 必须在标点分支之前拦截，整体当原子。
    // 粘性正则（/y）从当前位置试匹配，失败则 lastIndex 自动复位，不影响后续分支。
    MUSTACHE_RE.lastIndex = i;
    const mm = MUSTACHE_RE.exec(sql);
    if (mm) {
      out.push({ type: 'placeholder', value: mm[0], keyword: null });
      i += mm[0].length;
      continue;
    }
    COLON_RE.lastIndex = i;
    const cm = COLON_RE.exec(sql);
    if (cm) {
      out.push({ type: 'placeholder', value: cm[0], keyword: null });
      i += cm[0].length;
      continue;
    }

    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(sql[i + 1] ?? ''))) {
      let j = i;
      while (j < n && /[0-9]/.test(sql[j])) j++;
      if (sql[j] === '.') {
        j++;
        while (j < n && /[0-9]/.test(sql[j])) j++;
      }
      if (/[eE]/.test(sql[j] ?? '')) {
        j++;
        if (/[+-]/.test(sql[j] ?? '')) j++;
        while (j < n && /[0-9]/.test(sql[j])) j++;
      }
      out.push({ type: 'number', value: sql.slice(i, j), keyword: null });
      i = j;
      continue;
    }

    // 标识符 / 裸关键字（允许中文，MySQL 列名可以是中文）
    if (/[A-Za-z_-￿]/.test(c)) {
      let j = i;
      while (j < n && /[A-Za-z0-9_$-￿]/.test(sql[j])) j++;
      const value = sql.slice(i, j);
      const upper = value.toUpperCase();
      out.push({ type: 'word', value, keyword: KEYWORD.has(upper) ? upper : null });
      i = j;
      continue;
    }

    // 多字符运算符优先
    const two = sql.slice(i, i + 2);
    const three = sql.slice(i, i + 3);
    if (MULTI_OPS.includes(three)) {
      out.push({ type: 'punct', value: three, keyword: null });
      i += 3;
      continue;
    }
    if (MULTI_OPS.includes(two)) {
      out.push({ type: 'punct', value: two, keyword: null });
      i += 2;
      continue;
    }

    out.push({ type: 'punct', value: c, keyword: null });
    i++;
  }

  return out;
}

/** 语义指纹：忽略空白 / 注释，关键字统一大写。格式化前后的等价性校验。 */
function fingerprint(tokens: Token[]): string {
  return tokens
    .filter((t) => t.type !== 'ws' && t.type !== 'comment')
    .map((t) => `${t.type}:${t.keyword ? t.value.toUpperCase() : t.value}`)
    .join('');
}

/** 这些主句关键字之后是一串逗号分隔的列表，逗号应换行 */
const LIST_AFTER = new Set(['SELECT', 'FROM', 'GROUP BY', 'ORDER BY', 'VALUES', 'SET']);

export function formatSql(sql: string): string {
  if (!sql || !sql.trim()) return sql;

  const tokens = tokenize(sql);
  const before = fingerprint(tokens);

  // 含注释时保守退出：换行位置一变，行注释可能吞掉后面的代码，不值当
  if (tokens.some((t) => t.type === 'comment')) return sql;
  // 未闭合引号 = 用户正打到一半，不动
  if (hasUnterminatedQuote(sql)) return sql;

  const out: string[] = [];
  let depth = 0;
  /** 刚落到新行且还没写任何内容 —— 避免行首多一个空格 */
  let atLineStart = true;
  /** 刚写过 `.` 或 `(`，下一个 token 不加空格：a.b、sum(x) */
  let noSpaceNext = false;
  /** 逗号后是否处于「列表上下文」（SELECT / FROM / GROUP BY / ORDER BY 之后） */
  let inList = false;

  // emit 统一清掉 noSpaceNext：标记只对「紧接着的那一个 token」生效，
  // 否则 `a.b c` 会因为 b 之后的标记没被消费而粘成 `a.bc`。
  const emit = (s: string) => { out.push(s); atLineStart = false; noSpaceNext = false; };
  const space = () => { if (!atLineStart && !noSpaceNext) out.push(' '); };
  const consumeNoSpace = () => { noSpaceNext = false; };

  const newline = (indent: number) => {
    if (out.length) out.push('\n');
    const ind = INDENT.repeat(Math.max(0, indent));
    if (ind) out.push(ind);
    atLineStart = ind.length > 0;
  };

  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];

    if (t.type === 'ws') continue;

    if (t.type === 'word' && t.keyword) {
      const phrase = matchPhrase(tokens, i);
      const text = phrase.join(' ');
      const isMajor = MAJOR.has(text);

      if (isMajor) {
        consumeNoSpace();
        // 主句关键字回到当前层的缩进（顶层就是 0），和 FROM/SELECT 平级
        newline(depth);
        inList = LIST_AFTER.has(text);
      } else if (phrase.length > 1) {
        // ON DUPLICATE KEY UPDATE / IS NOT NULL 之类：整体紧跟前文
        consumeNoSpace();
        space();
        inList = false;
      } else {
        consumeNoSpace();
        space();
        if (!KEYWORD.has(text)) inList = false;
      }

      emit(text);
      i += phrase.length - 1;
      continue;
    }

    if (t.type === 'punct') {
      const v = t.value;

      if (v === '.') { emit('.'); noSpaceNext = true; continue; }   // a.b 不加空格
      if (v === '::') { consumeNoSpace(); space(); emit('::'); noSpaceNext = true; continue; }

      if (v === '(') {
        const prev = lastWordKeyword(tokens, i);
        consumeNoSpace();
        // IN (…)、VALUES (…)、AS (SELECT …) 保留空格；sum( 这类函数调用不留
        if (prev && SPACE_BEFORE_PAREN.has(prev)) space();
        emit('(');
        noSpaceNext = true;
        depth++;
        continue;
      }

      if (v === ')') {
        depth = Math.max(0, depth - 1);
        consumeNoSpace();
        emit(')');
        continue;                                          // ) 前不留空格
      }

      if (v === ',') {
        consumeNoSpace();
        emit(',');
        // 列表项换行缩进；函数参数 / 非列表保持同行 ——
        // 逗号后面<b>不要</b>自己塞空格，交给下一个 token 的 space()，
        // 否则会出现 `SELECT id,  sum(x)` 的双空格。
        if (inList && depth === 0) newline(1);
        continue;
      }

      if (v === ';') { consumeNoSpace(); emit(';'); newline(depth); inList = false; continue; }

      consumeNoSpace();
      space();
      emit(v);
      continue;
    }

    // 占位符 / 字符串 / 数字：整体透传
    // 占位符尤其要「前后都不加空格」—— 前面加了空格 :x 会变成 : x，
    // 后面加了空格 {{x}} 会变成 {{ x }}，两者都会让后端的占位符正则失配。
    space();
    emit(t.value);
  }

  const formatted = out
    .join('')
    .split('\n')
    .map((l) => l.replace(/\s+$/, ''))
    .filter((l) => l !== '')
    .join('\n')
    .trim();

  // 兜底：指纹不一致 → 我们动到了不该动的东西，原样返回
  if (fingerprint(tokenize(formatted)) !== before) return sql;

  return formatted;
}

/** 从位置 i 起尝试匹配多词关键字短语 */
function matchPhrase(tokens: Token[], i: number): string[] {
  for (const phrase of PHRASES) {
    let hit = true;
    for (let k = 0; k < phrase.length; k++) {
      const tk = tokens[i + k];
      if (!tk || tk.type !== 'word' || tk.keyword !== phrase[k]) { hit = false; break; }
    }
    if (hit) return phrase;
  }
  return [tokens[i].value.toUpperCase()];
}

/** 位置 i 之前最近一个关键字（用于判断 IN ( 还是 sum( ） */
function lastWordKeyword(tokens: Token[], i: number): string | null {
  for (let k = i - 1; k >= 0; k--) {
    const t = tokens[k];
    if (t.type === 'ws') continue;
    return t.keyword;
  }
  return null;
}

function hasUnterminatedQuote(sql: string): boolean {
  for (const q of ["'", '"', '`']) {
    let count = 0;
    for (let i = 0; i < sql.length; i++) {
      if (sql[i] !== q) continue;
      if (sql[i + 1] === q) { i++; continue; }
      count++;
    }
    if (count % 2 === 1) return true;
  }
  return false;
}