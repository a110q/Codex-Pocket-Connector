import path from 'node:path';

// Markdown destinations are URLs, but filesystem authorization uses native paths.
// Decode once and remove editor line/fragment suffixes before canonicalization.
export function localFilePath(link, root, paths = path) {
  if (typeof link !== 'string' || !link.trim() || link.includes('\0')) return null;
  let value = link.trim().replace(/^<([\s\S]*)>$/, '$1');
  if (/^file:/i.test(value)) {
    try {
      const url = new URL(value);
      if (url.hostname && url.hostname !== 'localhost') return null;
      value = url.pathname;
      if (paths.sep === '\\') value = value.replace(/^\/(?=[a-z]:)/i, '');
    } catch { return null; }
  } else if (/^[a-z][a-z\d+.-]*:/i.test(value) && !/^[a-z]:[\\/]/i.test(value)) return null;
  value = value.split(/[?#]/, 1)[0].replace(/:\d+(?::\d+)?$/, '');
  try { value = decodeURIComponent(value); } catch { return null; }
  value = value.replace(/\\([()])/g, '$1');
  if (!value || value.includes('\0')) return null;
  return paths.isAbsolute(value) ? paths.normalize(value) : root ? paths.resolve(root, value) : null;
}

export function linkedFilePaths(text, root, paths = path) {
  const result = new Set();
  const links = /!?\[[^\]\n]*\]\(\s*(?:<([^>\n]+)>|((?:\\[()]|[^()\n]|\([^()\n]*\))+))\s*\)/g;
  for (const match of String(text ?? '').matchAll(links)) {
    const destination = (match[1] ?? match[2]).trim().replace(/\s+["'][^\n]*["']$/, '');
    const file = localFilePath(destination, root, paths);
    if (file) result.add(file);
  }
  return [...result];
}
