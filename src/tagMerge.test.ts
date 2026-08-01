import { mergeDurableTags, upsertDurableTag } from './tagMerge';
import type { Tag } from './types';

const tag = (label: string, source: Tag['source'], score = 0.5): Tag => ({
  label,
  category: source === 'manual' || source === 'propagated' || source === 'exemplar' ? 'user' : 'topic',
  score,
  source,
});

describe('durable tag merging', () => {
  it('keeps every user-owned tag outside the auto-tag cap', () => {
    const durable = [
      tag('milady', 'manual', 1),
      tag('goblin', 'exemplar', 0.93),
      tag('same template', 'propagated', 0.91),
    ];
    const auto = [
      tag('one', 'ocr', 0.99),
      tag('two', 'vision', 0.98),
      tag('three', 'prompt', 0.97),
      tag('four', 'vision', 0.96),
      tag('five', 'prompt', 0.95),
    ];

    const merged = mergeDurableTags([...auto, ...durable], 2);

    expect(merged.map((t) => t.label)).toEqual(['milady', 'goblin', 'same template', 'one', 'two']);
  });

  it('does not let an auto tag with the same label erase durability', () => {
    const merged = mergeDurableTags(
      [tag('milady', 'exemplar', 0.75), tag('milady', 'ocr', 0.99), tag('filler', 'prompt', 0.9)],
      0
    );

    expect(merged).toEqual([tag('milady', 'exemplar', 0.75)]);
  });

  it('promotes an existing auto tag to the user-owned source when assigned', () => {
    const { tags, extraTerms } = upsertDurableTag(
      [tag('pepe', 'prompt', 0.61), tag('reaction', 'vision', 0.9)],
      'frog green',
      { label: 'Pepe', category: 'character', source: 'manual' }
    );

    expect(tags).toContainEqual({ label: 'pepe', category: 'character', score: 1, source: 'manual' });
    expect(tags.filter((t) => t.label === 'pepe')).toHaveLength(1);
    expect(extraTerms.split(/\s+/).sort()).toEqual(['frog', 'green', 'pepe']);
  });

  it('writes a taught tag as an exemplar immediately', () => {
    const { tags } = upsertDurableTag([], '', {
      label: 'Milady Maker',
      category: 'character',
      source: 'exemplar',
    });

    expect(tags).toEqual([{ label: 'milady maker', category: 'character', score: 1, source: 'exemplar' }]);
  });
});
