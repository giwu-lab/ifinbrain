#!/usr/bin/env node
/**
 * 把 frontend/src/i18n/locales/{zh-CN,en-US}.ts 平展成
 * backend/src/main/resources/i18n/defaults.json
 *
 * 输出格式（与 /api/system/i18n/import 一致）：
 * [
 *   {
 *     "namespace": "menu",
 *     "items": [
 *       { "code": "dashboard", "valueJson": "{\"zh-CN\":\"仪表盘\",\"en-US\":\"Dashboard\"}" },
 *       { "code": "invoice.List", "valueJson": "{\"zh-CN\":\"发票列表\",\"en-US\":\"Records\"}" }
 *     ]
 *   },
 *   ...
 * ]
 *
 * 注意：
 * - 「_」顶层键（命名空间自身的标题）单独存为 code = "_"
 * - 字符串值才被收集；对象/数组被递归；function 不收集
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FE_ROOT = path.resolve(__dirname, '..');
const BE_ROOT = path.resolve(FE_ROOT, '..', 'backend');

function loadTsObject(file) {
  const code = fs.readFileSync(file, 'utf-8');
  const cleaned = code.replace(/^export\s+default\s+/m, 'return ');
  // 用 eval 而非 vm（前端已有；denoise 简化版）
  return new Function(cleaned.replace(/^import .*$/gm, ''))();
}

/**
 * 把对象铺成 [{namespace, code, value}] 列表
 *   - key 含 . 的，code 仍包含（import 时按 . 分层）
 *   - 顶层 namespace 自身的标题存为 code = '_'
 */
function flatten(obj) {
  const out = [];
  function walk(prefix, value) {
    if (value === null || value === undefined) return;
    if (typeof value === 'string') {
      out.push({ namespace: prefix[0], code: prefix.slice(1).join('.') || '_', value });
      return;
    }
    if (typeof value === 'function') return;
    if (Array.isArray(value)) return; // i18n 不使用数组
    if (typeof value !== 'object') {
      // number / boolean 也跳过
      return;
    }
    for (const [k, v] of Object.entries(value)) {
      if (typeof v === 'string') {
        out.push({ namespace: prefix[0], code: [...prefix.slice(1), k].join('.') || '_', value: v });
      } else if (v && typeof v === 'object') {
        walk([...prefix, k], v);
      }
    }
  }
  // 顶层每个键就是 namespace
  for (const [ns, sub] of Object.entries(obj)) {
    if (!sub || typeof sub !== 'object' || Array.isArray(sub)) continue;
    for (const [k, v] of Object.entries(sub)) {
      if (typeof v === 'string') {
        // 直接键如 dashboard: '仪表盘'
        out.push({ namespace: ns, code: k, value: v });
      } else if (v && typeof v === 'object') {
        // 嵌套对象如 invoice.List: '...' 
        for (const [k2, v2] of Object.entries(v)) {
          if (typeof v2 === 'string') {
            out.push({ namespace: ns, code: `${k}.${k2}`, value: v2 });
          } else if (v2 && typeof v2 === 'object') {
            // 3 层及以上
            for (const [k3, v3] of Object.entries(v2)) {
              if (typeof v3 === 'string') {
                out.push({ namespace: ns, code: `${k}.${k2}.${k3}`, value: v3 });
              }
            }
          }
        }
      }
    }
  }
  return out;
}

function main2() {
  const zhFile = path.join(FE_ROOT, 'src/i18n/locales/zh-CN.ts');
  const enFile = path.join(FE_ROOT, 'src/i18n/locales/en-US.ts');
  if (!fs.existsSync(zhFile) || !fs.existsSync(enFile)) {
    console.error('i18n locale files not found:', zhFile, enFile);
    process.exit(1);
  }

  const zh = loadTsObject(zhFile);
  const en = loadTsObject(enFile);

  const zhFlat = flatten(zh);
  const enFlat = flatten(en);

  // 联合 key
  const codeMap = new Map(); // key -> {namespace, code}
  for (const it of [...zhFlat, ...enFlat]) {
    const k = `${it.namespace}|${it.code}`;
    if (!codeMap.has(k)) codeMap.set(k, { namespace: it.namespace, code: it.code });
  }

  const zhMap = new Map(zhFlat.map((x) => [`${x.namespace}|${x.code}`, x.value]));
  const enMap = new Map(enFlat.map((x) => [`${x.namespace}|${x.code}`, x.value]));

  // 按 namespace 分组
  const grouped = new Map();
  for (const [k, { namespace, code }] of codeMap) {
    const zhVal = zhMap.get(k) || '';
    const enVal = enMap.get(k) || '';
    const valueJson = JSON.stringify({ 'zh-CN': zhVal, 'en-US': enVal });
    if (!grouped.has(namespace)) grouped.set(namespace, []);
    grouped.get(namespace).push({ code, valueJson });
  }

  // 输出数组（按 namespace 字母序）
  const out = [...grouped.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([namespace, items]) => ({
      namespace,
      items: items.sort((a, b) => a.code.localeCompare(b.code)),
    }));

  const outDir = path.join(BE_ROOT, 'src/main/resources/i18n');
  fs.mkdirSync(outDir, { recursive: true });
  const outFile = path.join(outDir, 'defaults.json');
  fs.writeFileSync(outFile, JSON.stringify(out, null, 2) + '\n', 'utf-8');

  const total = out.reduce((s, n) => s + n.items.length, 0);
  console.log(`[i18n-export] wrote ${outFile}`);
  console.log(`[i18n-export] namespaces: ${out.length}, total keys: ${total}`);
  for (const ns of out) {
    console.log(`  - ${ns.namespace}: ${ns.items.length} keys`);
  }
}

main2();