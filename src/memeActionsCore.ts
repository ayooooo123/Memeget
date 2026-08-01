import { extOf, videoMimeFor } from './mediaFormats';

export interface CopyTarget {
  transcode: boolean;
  name: string;
  mimeType: string;
}

function replaceExtension(name: string, extension: string): string {
  const dot = name.lastIndexOf('.');
  const base = dot > 0 ? name.slice(0, dot) : name;
  return `${base}.${extension}`;
}

export function compatibleCopyTarget(name: string, kind: 'image' | 'video'): CopyTarget {
  if (kind === 'video' && extOf(name) === 'webm') {
    return { transcode: true, name: replaceExtension(name, 'mp4'), mimeType: 'video/mp4' };
  }
  return {
    transcode: false,
    name,
    mimeType: kind === 'video' ? videoMimeFor(name) : 'image/*',
  };
}

export function makeVariationName(sourceName: string, extension: 'png' | 'mp4', now = Date.now()): string {
  const dot = sourceName.lastIndexOf('.');
  const rawBase = dot > 0 ? sourceName.slice(0, dot) : sourceName;
  const base = rawBase.replace(/[^a-zA-Z0-9_-]+/g, '_').replace(/^_+|_+$/g, '') || 'meme';
  const date = new Date(now);
  const stamp = [
    date.getUTCFullYear(),
    String(date.getUTCMonth() + 1).padStart(2, '0'),
    String(date.getUTCDate()).padStart(2, '0'),
    '-',
    String(date.getUTCHours()).padStart(2, '0'),
    String(date.getUTCMinutes()).padStart(2, '0'),
    String(date.getUTCSeconds()).padStart(2, '0'),
  ].join('');
  return `${base}-variation-${stamp}.${extension}`;
}

export function formatBuildLabel(version: string | null | undefined, build: string | number | null | undefined): string {
  const cleanVersion = version?.trim();
  const cleanBuild = build == null ? '' : String(build).trim();
  if (!cleanVersion && !cleanBuild) return 'Memeget · development build';
  return `Memeget${cleanVersion ? ` ${cleanVersion}` : ''}${cleanBuild ? ` · build ${cleanBuild}` : ''}`;
}
