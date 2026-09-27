/**
 * 文件类型检测 —— 给 FilePreviewer.vue 做分发依据。
 * 不依赖后端 MIME（前端可纯客户端判断预览方式）。
 */

export type PreviewKind =
  | 'image'
  | 'pdf'
  | 'xml'
  | 'code-text'
  | 'docx'
  | 'xlsx'
  | 'audio'
  | 'video'
  | 'ofd'
  | 'unsupported';

const IMAGE_EXT = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'];
const CODE_TEXT_EXT = ['txt', 'csv', 'json', 'md', 'sql', 'log', 'yml', 'yaml', 'tsv', 'ini', 'conf'];
const AUDIO_EXT = ['mp3', 'wav', 'ogg', 'm4a', 'flac'];
const VIDEO_EXT = ['mp4', 'webm', 'mov', 'avi', 'mkv'];

export function detectKind(filename: string): PreviewKind {
  if (!filename) return 'unsupported';
  // 去掉查询参数和路径，只留扩展名
  const name = filename.split('?')[0].split('#')[0];
  const ext = (name.split('.').pop() || '').toLowerCase();
  if (!ext) return 'unsupported';

  if (ext === 'pdf') return 'pdf';
  if (ext === 'xml') return 'xml';
  if (ext === 'ofd') return 'ofd';
  if (ext === 'docx') return 'docx';
  if (ext === 'xlsx' || ext === 'xls') return 'xlsx';

  if (IMAGE_EXT.includes(ext)) return 'image';
  if (CODE_TEXT_EXT.includes(ext)) return 'code-text';
  if (AUDIO_EXT.includes(ext)) return 'audio';
  if (VIDEO_EXT.includes(ext)) return 'video';

  return 'unsupported';
}

/** Monaco 编辑器使用的语言 id —— code-text 类文件的语法高亮 */
export function detectMonacoLang(filename: string): string {
  if (!filename) return 'plaintext';
  const ext = (filename.split('?')[0].split('.').pop() || '').toLowerCase();
  const map: Record<string, string> = {
    txt: 'plaintext',
    csv: 'plaintext',
    tsv: 'plaintext',
    json: 'json',
    md: 'markdown',
    sql: 'sql',
    log: 'plaintext',
    yml: 'yaml',
    yaml: 'yaml',
    ini: 'ini',
    conf: 'plaintext',
  };
  return map[ext] || 'plaintext';
}