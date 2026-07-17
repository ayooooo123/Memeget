import { planZipImport, type ZipEntryMeta } from './zipImportCore';

const entry = (path: string, isDir = false): ZipEntryMeta => ({ path, isDir });

describe('planZipImport', () => {
  it('keeps only image/video formats Memeget handles', () => {
    const plan = planZipImport(
      [
        entry('a.jpg'),
        entry('b.PNG'), // case-insensitive extension
        entry('c.mp4'),
        entry('readme.txt'),
        entry('notes.pdf'),
        entry('song.mp3'),
      ],
      []
    );
    expect(plan.imports.map((i) => i.name)).toEqual(['a.jpg', 'b.PNG', 'c.mp4']);
    expect(plan.unsupported.sort()).toEqual(['notes.pdf', 'readme.txt', 'song.mp3']);
    expect(plan.duplicates).toEqual([]);
  });

  it('tags kind from the extension', () => {
    const plan = planZipImport([entry('pic.gif'), entry('clip.webm')], []);
    expect(plan.imports).toEqual([
      { path: 'pic.gif', name: 'pic.gif', kind: 'image' },
      { path: 'clip.webm', name: 'clip.webm', kind: 'video' },
    ]);
  });

  it('skips files whose name already exists in the folder (case-insensitive)', () => {
    const plan = planZipImport([entry('Frog.jpg'), entry('new.jpg')], ['frog.jpg']);
    expect(plan.imports.map((i) => i.name)).toEqual(['new.jpg']);
    expect(plan.duplicates).toEqual(['Frog.jpg']);
  });

  it('skips duplicates within the same archive, keeping the first occurrence', () => {
    const plan = planZipImport(
      [entry('memes/a.jpg'), entry('more/a.jpg'), entry('b.jpg')],
      []
    );
    expect(plan.imports.map((i) => i.path)).toEqual(['memes/a.jpg', 'b.jpg']);
    expect(plan.duplicates).toEqual(['a.jpg']);
  });

  it('uses the basename for the display name but the full path to fetch bytes', () => {
    const plan = planZipImport([entry('deep/nested/dank.png')], []);
    expect(plan.imports).toEqual([
      { path: 'deep/nested/dank.png', name: 'dank.png', kind: 'image' },
    ]);
  });

  it('ignores directory entries', () => {
    const plan = planZipImport([entry('folder/', true), entry('folder/x.jpg')], []);
    expect(plan.imports.map((i) => i.name)).toEqual(['x.jpg']);
  });

  it('silently drops archive cruft (macOS forks, dotfiles, Thumbs.db)', () => {
    const plan = planZipImport(
      [
        entry('__MACOSX/._a.jpg'),
        entry('._b.jpg'),
        entry('.DS_Store'),
        entry('Thumbs.db'),
        entry('real.jpg'),
      ],
      []
    );
    expect(plan.imports.map((i) => i.name)).toEqual(['real.jpg']);
    // Cruft is noise, not "unsupported memes" the user should be told about.
    expect(plan.unsupported).toEqual([]);
    expect(plan.duplicates).toEqual([]);
  });

  it('handles backslash-separated paths from Windows-made zips', () => {
    const plan = planZipImport([entry('sub\\win.jpg')], []);
    expect(plan.imports).toEqual([{ path: 'sub\\win.jpg', name: 'win.jpg', kind: 'image' }]);
  });
});
