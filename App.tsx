import React, { useEffect, useState } from 'react';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { EmbeddingsProvider } from './src/embeddings';
import { initDb } from './src/db';
import { LibraryScreen } from './src/screens/LibraryScreen';
import { SearchScreen } from './src/screens/SearchScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { colors } from './src/theme';

type TabKey = 'library' | 'search' | 'settings';

const TABS: { key: TabKey; label: string; icon: string }[] = [
  { key: 'library', label: 'Library', icon: '🗂️' },
  { key: 'search', label: 'Search', icon: '🔍' },
  { key: 'settings', label: 'Settings', icon: '⚙️' },
];

export default function App() {
  const [tab, setTab] = useState<TabKey>('library');
  const [dbReady, setDbReady] = useState(false);

  useEffect(() => {
    initDb()
      .then(() => setDbReady(true))
      .catch((e) => console.warn('DB init failed', e));
  }, []);

  return (
    <SafeAreaProvider>
      <EmbeddingsProvider>
        <StatusBar style="light" />
        <SafeAreaView style={styles.root} edges={['top', 'left', 'right']}>
          <View style={styles.header}>
            <Text style={styles.brand}>Memeget</Text>
            <Text style={styles.tagline}>private, on-device meme search</Text>
          </View>

          <View style={styles.body}>
            {!dbReady ? (
              <View style={styles.center}>
                <Text style={styles.muted}>Starting up…</Text>
              </View>
            ) : tab === 'library' ? (
              <LibraryScreen />
            ) : tab === 'search' ? (
              <SearchScreen />
            ) : (
              <SettingsScreen />
            )}
          </View>

          <View style={styles.tabbar}>
            {TABS.map((t) => {
              const active = t.key === tab;
              return (
                <Pressable key={t.key} style={styles.tab} onPress={() => setTab(t.key)}>
                  <Text style={[styles.tabIcon, active && styles.tabIconActive]}>{t.icon}</Text>
                  <Text style={[styles.tabLabel, active && styles.tabLabelActive]}>{t.label}</Text>
                </Pressable>
              );
            })}
          </View>
        </SafeAreaView>
      </EmbeddingsProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  header: { paddingHorizontal: 16, paddingTop: 8, paddingBottom: 12 },
  brand: { color: colors.text, fontSize: 26, fontWeight: '800', letterSpacing: 0.5 },
  tagline: { color: colors.muted, fontSize: 12, marginTop: 2 },
  body: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  muted: { color: colors.muted },
  tabbar: {
    flexDirection: 'row',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
    backgroundColor: colors.surface,
    paddingBottom: 8,
    paddingTop: 6,
  },
  tab: { flex: 1, alignItems: 'center', gap: 2 },
  tabIcon: { fontSize: 20, opacity: 0.5 },
  tabIconActive: { opacity: 1 },
  tabLabel: { color: colors.muted, fontSize: 11 },
  tabLabelActive: { color: colors.accent, fontWeight: '700' },
});
