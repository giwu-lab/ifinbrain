/**
 * 表达式沙箱 —— 移植自 qiyuan `barda-core/src/eval/utils/evalScript.tsx`。
 *
 * 机制：`new Function("with(this){ ... }")` + Proxy 拦截。`with` 把作用域查找
 * 全部导向 `this`，Proxy 的 `has`/`get` 陷阱再决定每个标识符从哪来 ——
 * 用户上下文 → 受限宿主全局 → 黑洞。
 *
 * ---------------------------------------------------------------------------
 * 与上游的三处刻意偏离（重要，改前请先读）
 * ---------------------------------------------------------------------------
 *
 * 1. **上下文套只读视图，而不是 Object.freeze。**
 *    上游 `proxySandbox.get` 里有 `Object.freeze(value)`，因为 Barda 的 context
 *    是一次性只读快照。本项目的 context 是 `FormRenderer` 里**活的 reactive
 *    formData** —— 对它 freeze 会落到 Vue 的底层 target 上，之后所有写入静默
 *    失效，表单直接变成改不动的假死状态，比放行写入更糟。
 *    改为返回一层只读代理：读穿透到原对象（Vue 依赖追踪照常生效），写抛错。
 *    这同时堵住了上游没有覆盖的一类漏洞 —— 直接改写上下文字段会**静默污染
 *    业务数据**，界面上完全看不出来。详见 readOnlyView 的注释。
 *
 * 2. **不做跨次调用的 mockWindow 缓存。**
 *    上游用一个模块级 `mockWindow` 复用宿主全局代理。本项目表达式求值频次
 *    远低于 Barda（大屏几百个组件），缓存带来的收益抵不上「跨表单串味」的
 *    排查成本，每次调用建一个轻量代理更简单。
 *
 * 3. **加了编译产物缓存（COMPILE_CACHE）。**
 *    上游靠 Node 层的 memoized 规避重复编译。本项目没有 Node 层，就直接在
 *    这里按代码串缓存 —— 生成的函数体是 `with(this){...}`，不捕获调用时上下文，
 *    所以跨上下文复用是安全的。
 */

/** 函数级作用域黑名单：禁止触达宿主对象与网络能力 */
const FUNCTION_BLACKLIST = new Set<PropertyKey>([
  'top',
  'parent',
  'document',
  'location',
  'chrome',
  'fetch',
  'XMLHttpRequest',
  'importScripts',
  'Navigator',
  'MutationObserver',
]);

/** 表达式作用域黑名单：在函数黑名单基础上再加定时器（防止死循环拖垮页面） */
const EXPRESSION_BLACKLIST = new Set<PropertyKey>([
  ...FUNCTION_BLACKLIST,
  'setTimeout',
  'setInterval',
  'setImmediate',
]);

/** 这些全局别名一律回指沙箱自身，堵死 `window.constructor.constructor('...')()` 这类逃逸 */
const GLOBAL_VAR_NAMES = new Set<PropertyKey>(['window', 'globalThis', 'self', 'global']);

export type SandboxScope = 'function' | 'expression';

export interface SandboxOption {
  /** scope=function 用函数黑名单，否则用表达式黑名单（多禁定时器） */
  scope?: SandboxScope;
  /**
   * 完全绕过 Proxy 沙箱，直接以普通对象作 this 执行。
   * 消除全部 Proxy 陷阱开销。仅用于「设计态高频重算」这类纯本地、可信场景。
   */
  noSandbox?: boolean;
  /**
   * 允许表达式改写上下文里的对象（`$form.total = a + b` 这类派生字段写法）。
   *
   * 默认**禁止**：表达式写在 label / placeholder 这种展示位，用户手滑打个 `=`
   * 就会静默改掉业务数据，且界面上完全看不出发生了什么 —— 这类 bug 极难排查。
   * 需要「计算字段」能力时（M2）再显式打开，别默认放开。
   */
  allowContextMutation?: boolean;
}

/**
 * 只读视图缓存：同一目标对象复用同一个代理，避免每次属性访问都新建 Proxy */
const READONLY_CACHE = new WeakMap<object, object>();

/**
 * 只对「纯对象 / 数组」套只读视图。
 *
 * 刻意放过 Date / Map / Set 这类带内部槽的对象：它们的方法读的是 [[DateValue]]
 * 之类，拿到 Proxy 会直接抛 "this is not a Date object"。表单数据以普通对象和
 * 数组为主，这些对象被误写的风险也最低。
 */
function shouldWrap(value: unknown): boolean {
  if (value === null || typeof value !== 'object') return false;
  if (Array.isArray(value)) return true;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

/**
 * 给上下文里的对象套一层**只读视图**。
 *
 * 为什么不能像 Barda 那样直接 `Object.freeze(context)`：
 * 本项目的上下文是 FormRenderer 里**活的 reactive formData**，freeze 会落到
 * 底层 target 上，Vue 之后所有对该字段的写入都会静默失败 —— 表单直接变成
 * 「改不动」的假死状态，比放行写入更糟。
 *
 * 所以改为：读穿透到原对象（Vue 的依赖追踪照常生效），写一律抛错。
 * 这样既堵住了 `$form.name = 1` 这种静默污染，又不破坏响应式。
 *
 * ⚠️ 方法**不要 bind 到原对象**：`arr.push(x)` 若绑到 raw target，写入会直接
 * 落到真数组上、绕过代理的 set 陷阱（实测踩过）。原样返回方法即可 —— 调用点
 * `view.arr.push(...)` 会天然把 this 设为 view，写入照样被拦。
 */
function readOnlyView(target: any): any {
  if (target === null || typeof target !== 'object') return target;
  if (!shouldWrap(target)) return target;

  const cached = READONLY_CACHE.get(target);
  if (cached) return cached;

  const view = new Proxy(target, {
    get(t, p) {
      if (p === Symbol.toPrimitive) return () => '';
      if (p === 'toJSON') return () => t;
      // receiver 传 t 本身而不是 view：Vue 的 getter 若被要求写到 view 上会失败
      const v = Reflect.get(t, p, t);
      return shouldWrap(v) ? readOnlyView(v) : v;
    },
    set() {
      throw new Error('expression cannot modify context value');
    },
    deleteProperty() {
      throw new Error('expression cannot delete context value');
    },
    defineProperty() {
      throw new Error('expression cannot define property on context value');
    },
    setPrototypeOf() {
      throw new Error('expression cannot set prototype of context value');
    },
  });

  READONLY_CACHE.set(target, view);
  return view;
}

/**
 * 黑洞对象：任何属性访问都返回它自己，任何调用都返回它自己。
 *
 * 用途是被禁能力的「吸收体」—— `{{ fetch('/x') }}` 不会抛错，而是静默拿到
 * 一个黑洞继续求值。相比抛异常，这样单个坏表达式不会打断整串文本的渲染。
 */
export function createBlackHole(): any {
  return new Proxy(function () { return createBlackHole(); }, {
    get(_t, p) {
      // toString 返回空串：黑洞被塞进文本拼接时不至于打印出 [object Object] 噪音
      if (p === 'toString') return () => '';
      if (p === Symbol.toPrimitive) return () => '';
      return createBlackHole();
    },
  });
}

function isDomElement(obj: unknown): boolean {
  return typeof Element !== 'undefined' && obj instanceof Element
    || typeof HTMLCollection !== 'undefined' && obj instanceof HTMLCollection;
}

/** 取宿主全局对象上的属性；DOM 节点返回 undefined（序列化会抛错） */
function getPropertyFromHost(prop: PropertyKey) {
  const ret = Reflect.get(globalThis, prop);
  if (typeof ret === 'function' && !ret.prototype) {
    // 绑定 this，否则 Math.min / JSON.stringify 之类脱离全局会报错
    return ret.bind(globalThis);
  }
  if (isDomElement(ret)) return undefined;
  return ret;
}

/**
 * 构造求值用的 `this`。
 *
 * `has` 恒返回 true 是 `with` 语句能接管所有标识符的前提 —— 若返回 false，
 * `Math` 之类会直接走外层词法作用域，sandbox 的黑名单就形同虚设。
 */
function createSandbox(context: Record<string, unknown>, options?: SandboxOption) {
  const blacklist = options?.scope === 'function' ? FUNCTION_BLACKLIST : EXPRESSION_BLACKLIST;
  const mutable = options?.allowContextMutation === true;

  const isProtected = (key: PropertyKey) =>
    Object.prototype.hasOwnProperty.call(context, key) || GLOBAL_VAR_NAMES.has(key);

  return new Proxy({} as Record<PropertyKey, unknown>, {
    has() {
      return true;
    },
    get(_target, p, receiver) {
      if (p === Symbol.unscopables) return undefined;
      if (p === 'toJSON') return undefined;

      // window / globalThis / self / global 一律回指沙箱，堵住 global 逃逸
      if (GLOBAL_VAR_NAMES.has(p)) return receiver;

      if (Object.prototype.hasOwnProperty.call(context, p)) {
        const v = Reflect.get(context, p, receiver);
        // 只读视图：读穿透（保住响应式），写抛错（防静默污染业务数据）
        return mutable ? v : readOnlyView(v);
      }
      if (blacklist.has(p)) return createBlackHole();

      return getPropertyFromHost(p);
    },
    set(_target, p, value, receiver) {
      if (isProtected(p)) {
        throw new Error(String(p) + " can't be modified");
      }
      return Reflect.set(_target, p, value, receiver);
    },
    defineProperty(_target, p, attributes) {
      if (isProtected(p)) {
        throw new Error("can't define property: " + String(p));
      }
      return Reflect.defineProperty(_target, p, attributes);
    },
    deleteProperty(_target, p) {
      if (isProtected(p)) {
        throw new Error("can't delete property: " + String(p));
      }
      return Reflect.deleteProperty(_target, p);
    },
    setPrototypeOf() {
      throw new Error("can't invoke setPrototypeOf");
    },
  });
}

/**
 * 编译产物缓存。
 *
 * 安全前提：生成的函数体是 `with(this){...}`，**不捕获调用时的上下文** ——
 * 上下文永远是 `.call(ctx)` 传进去的 `this`。所以同一段表达式源码编译出的
 * 函数可以跨表单、跨上下文复用，无需按上下文再分键。
 *
 * 表单渲染时每次响应式变更都会重跑一遍属性求值，没有这层缓存就是每次重渲染
 * 都 `new Function` 一遍。上限 500 条，超了整体清空（表达式文本基数很小，
 * 实际场景撑不满；真撑满了也只是退回无缓存，不会出错）。
 */
const COMPILE_CACHE = new Map<string, Function>();
const COMPILE_CACHE_CAP = 500;

function compile(code: string): Function {
  const cached = COMPILE_CACHE.get(code);
  if (cached) return cached;

  // eslint-disable-next-line no-new-func
  const vm = new Function(code);

  if (COMPILE_CACHE.size >= COMPILE_CACHE_CAP) COMPILE_CACHE.clear();
  COMPILE_CACHE.set(code, vm);
  return vm;
}

/**
 * 在沙箱里执行一段函数体。
 *
 * 生成的代码里，`with(this){...}` 外层**不加** 'use strict'（with 在严格模式下
 * 是语法错误），只有内层函数体加 —— 这样内层仍是严格模式（用户代码拿不到
 * 隐式全局变量），而外层仍能让 with 生效。
 */
export function evalFunc(
  functionBody: string,
  context: Record<string, unknown>,
  options?: SandboxOption,
): unknown {
  const vm = compile(`with(this){
    return (function() {
      'use strict';
      ${functionBody};
    }).call(this);
  }`);

  if (options?.noSandbox) {
    // 无沙箱：普通对象作 this，宿主全局走词法作用域（零 Proxy 开销）
    return vm.call(context);
  }
  return vm.call(createSandbox(context, options));
}

/** 求值一个表达式（自动包成 `return (expr);`） */
export function evalScript(
  script: string,
  context: Record<string, unknown>,
  options?: SandboxOption,
): unknown {
  return evalFunc(`return (\n${script}\n);`, context, options);
}