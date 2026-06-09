import React, { useCallback, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { MemeGrid } from '../components/MemeGrid';
import { useEmbeddings } from '../embeddings';
import { countMemesWithLabel, searchByVector } from '../db';
import { retagAll } from '../indexer';
import { colors } from '../theme';
import type { SearchHit } from '../types';

const SUGGESTIONS = [
  'crying wojak',
  'gigachad',
  'this is fine',
  'distracted boyfriend',
  'programming bug',
  'sad cat',
];

export function SearchScreen() {
  const emb = useEmbeddings();
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [busy, setBusy] = useState(false);
  const [searched, setSearched] = useState(false);

  const run = useCallback(
    async (q: string) => {
      const text = q.trim();
      if (!text) return;
      if (!emb.ready) return;
      setBusy(true);
      setSearched(true);
      try {
        const vec = await emb.embedText(text);
        setHits(await searchByVector(vec, text));
      } finally {
        setBusy(false);
      }
    },
    [emb]
  );

  const header = (
    <View style={styles.header}>
      <View style={styles.inputRow}>
        <TextInput
          style={styles.input}
          value={query}
          onChangeText={setQuery}
          placeholder="Describe the meme you want…"
          placeholderTextColor={colors.muted}
          returnKeyType="search"
          onSubmitEditing={() => run(query)}
        />
        <Pressable style={styles.searchBtn} onPress={() => run(query)}>
          <Text style={styles.searchBtnText}>Go</Text>
        </Pressable>
      </View>

      {!emb.ready && <Text style={styles.muted}>Model still loading — search will work once it’s ready.</Text>}

      {!searched && (
        <View style={styles.suggestRow}>
          {SUGGESTIONS.map((s) => (
            <Pressable
              key={s}
              style={styles.suggest}
              onPress={() => {
                setQuery(s);
                run(s);
              }}
            >
              <Text style={styles.suggestText}>{s}</Text>
            </Pressable>
          ))}
        </View>
      )}

      {busy && (
        <View style={styles.busy}>
          <ActivityIndicator color={colors.accent} />
          <Text style={styles.muted}>Searching on-device…</Text>
        </View>
      )}

      {searched && !busy && hits.length === 0 && (
        <Text style={styles.muted}>No matches. Index some memes in the Library tab first.</Text>
      )}
    </View>
  );

  const onTaught = useCallback(
    async (label: string) => {
      if (emb.ready) await retagAll(emb);
      if (query.trim()) await run(query);
      return countMemesWithLabel(label);
    },
    [emb, query, run]
  );

  return <MemeGrid items={hits} header={header} onTaught={onTaught} />;
}

const styles = StyleSheet.create({
  header: { padding: 12, gap: 12 },
  inputRow: { flexDirection: 'row', gap: 8 },
  input: {
    flex: 1,
    backgroundColor: colors.surface2,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 11,
    color: colors.text,
    fontSize: 15,
  },
  searchBtn: { backgroundColor: colors.accent, borderRadius: 12, paddingHorizontal: 18, justifyContent: 'center' },
  searchBtnText: { color: '#0b0d12', fontWeight: '800' },
  muted: { color: colors.muted, fontSize: 12 },
  suggestRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  suggest: { backgroundColor: colors.chip, borderRadius: 14, paddingHorizontal: 12, paddingVertical: 6 },
  suggestText: { color: colors.accent, fontSize: 13, fontWeight: '600' },
  busy: { flexDirection: 'row', alignItems: 'center', gap: 8 },
});
