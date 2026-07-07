import React, { useEffect, useState } from 'react';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider, SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { Animated, Pressable, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { initExecutorch } from 'react-native-executorch';
import { ExpoResourceFetcher } from 'react-native-executorch-expo-resource-fetcher';

import { AudioProvider } from './src/audio';
import { EmbeddingsProvider } from './src/embeddings';
import { VisionProvider } from './src/vision';
import { initDb, getSetting, setSetting } from './src/db';
import { sweepStaleCache } from './src/saf';
import { useConst } from './src/reactUtils';
import { LibraryScreen } from './src/screens/LibraryScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { ShareReceiver } from './src/components/ShareReceiver';
import { ToastHost } from './src/components/Toast';
import { tap } from './src/haptics';
import { colors, radius, shadow, TABBAR_CLEARANCE } from './src/theme';

// Must run once, before any model hook loads a model. Wires ExecuTorch's
// resource fetcher to Expo's filesystem so model binaries can be downloaded
// and cached on-device.
initExecutorch({ resourceFetcher: ExpoResourceFetcher });

// Bumped if we ever need to force another legacy-cache reclaim.
const CACHE_PURGE_KEY = 'expo_image_disk_cache_purged_v1';

// Reclaim cache-dir space in the background (never blocks first paint):
//   • Every launch, drop our own leaked temp files (share_/import_/meme_work_).
//   • Once per install, purge the legacy expo-image disk cache the old
//     `cachePolicy="disk"` left behind — it held a full duplicate of the
//     library and is what made the app's cache balloon over repeated use. We're
//     memory-only now, so nothing repopulates it; clearing once is enough.
async function maintainCaches(): Promise<void> {
  try {
    await sweepStaleCache();
    if ((await getSetting(CACHE_PURGE_KEY)) !== '1') {
      await Image.clearDiskCache().catch(() => {});
      await setSetting(CACHE_PURGE_KEY, '1').catch(() => {});
    }
  } catch {
    // best-effort housekeeping; failures must never affect the app
  }
}

type TabKey = 'library' | 'settings';

const TABS: { key: TabKey; label: string; glyph: string }[] = [
  { key: 'library', label: 'Library', glyph: '▦' },
  { key: 'settings', label: 'Settings', glyph: '◎' },
];

export default function App() {
  return (
    <SafeAreaProvider>
      <EmbeddingsProvider>
        <VisionProvider>
          <AudioProvider>
            <StatusBar style="light" />
            <Shell />
          </AudioProvider>
        </VisionProvider>
      </EmbeddingsProvider>
    </SafeAreaProvider>
  );
}

function Shell() {
  const insets = useSafeAreaInsets();
  const [tab, setTab] = useState<TabKey>('library');
  const [dbReady, setDbReady] = useState(false);

  useEffect(() => {
    initDb()
      .then(() => {
        setDbReady(true);
        // Fire-and-forget so cache housekeeping never delays first paint.
        maintainCaches();
      })
      .catch((e) => console.warn('DB init failed', e));
  }, []);

  return (
    <SafeAreaView style={styles.root} edges={['top', 'left', 'right']}>
      {!dbReady ? (
        <Boot />
      ) : (
        // Both screens stay mounted so the Library keeps its scroll position,
        // search text, and results across tab switches.
        <View style={styles.body}>
          <View style={[styles.screen, tab !== 'library' && styles.hidden]}>
            <LibraryScreen />
          </View>
          <View style={[styles.screen, tab !== 'settings' && styles.hidden]}>
            <SettingsScreen active={tab === 'settings'} />
          </View>
        </View>
      )}

      <TabBar tab={tab} onChange={setTab} bottomInset={insets.bottom} />
      <ShareReceiver />
      <ToastHost bottomOffset={TABBAR_CLEARANCE + insets.bottom + 8} />
    </SafeAreaView>
  );
}

function Boot() {
  const pulse = useConst(() => new Animated.Value(0.4));
  useEffect(() => {
    Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1, duration: 700, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 0.4, duration: 700, useNativeDriver: true }),
      ])
    ).start();
  }, [pulse]);
  return (
    <View style={styles.boot}>
      <Text style={styles.bootBrand}>
        Memeget<Text style={styles.bootDot}>.</Text>
      </Text>
      <Animated.Text style={[styles.bootHint, { opacity: pulse }]}>opening your stash…</Animated.Text>
    </View>
  );
}

// Floating pill tab bar with a sliding active indicator.
function TabBar({
  tab,
  onChange,
  bottomInset,
}: {
  tab: TabKey;
  onChange: (t: TabKey) => void;
  bottomInset: number;
}) {
  const idx = TABS.findIndex((t) => t.key === tab);
  const slide = useConst(() => new Animated.Value(idx));
  const [segWidth, setSegWidth] = useState(0);

  useEffect(() => {
    Animated.spring(slide, { toValue: idx, useNativeDriver: true, speed: 22, bounciness: 7 }).start();
  }, [idx, slide]);

  return (
    <View pointerEvents="box-none" style={[styles.tabWrap, { bottom: Math.max(bottomInset, 10) + 8 }]}>
      <View
        style={styles.tabbar}
        onLayout={(e) => setSegWidth(e.nativeEvent.layout.width / TABS.length)}
      >
        {segWidth > 0 && (
          <Animated.View
            style={[
              styles.indicator,
              {
                width: segWidth - 8,
                transform: [
                  {
                    translateX: slide.interpolate({
                      inputRange: [0, TABS.length - 1],
                      outputRange: [4, segWidth * (TABS.length - 1) + 4],
                    }),
                  },
                ],
              },
            ]}
          />
        )}
        {TABS.map((t) => {
          const active = t.key === tab;
          return (
            <Pressable
              key={t.key}
              style={styles.tab}
              onPress={() => {
                if (t.key !== tab) tap();
                onChange(t.key);
              }}
              accessibilityRole="tab"
              accessibilityState={{ selected: active }}
            >
              <Text style={[styles.tabGlyph, active && styles.tabGlyphActive]}>{t.glyph}</Text>
              <Text style={[styles.tabLabel, active && styles.tabLabelActive]}>{t.label}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  body: { flex: 1 },
  screen: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  hidden: { display: 'none' },

  boot: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10 },
  bootBrand: { color: colors.text, fontSize: 34, fontWeight: '800', letterSpacing: -1 },
  bootDot: { color: colors.volt },
  bootHint: { color: colors.muted, fontSize: 13, fontWeight: '600' },

  tabWrap: { position: 'absolute', left: 0, right: 0, alignItems: 'center' },
  tabbar: {
    flexDirection: 'row',
    backgroundColor: colors.surface2,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.borderLight,
    paddingVertical: 6,
    width: 240,
    ...shadow.float,
  },
  indicator: {
    position: 'absolute',
    top: 4,
    bottom: 4,
    borderRadius: radius.pill,
    backgroundColor: colors.surface3,
    borderWidth: 1,
    borderColor: colors.borderLight,
  },
  tab: { flex: 1, alignItems: 'center', gap: 1, paddingVertical: 3 },
  tabGlyph: { color: colors.muted, fontSize: 16 },
  tabGlyphActive: { color: colors.volt },
  tabLabel: { color: colors.muted, fontSize: 11, fontWeight: '600' },
  tabLabelActive: { color: colors.text, fontWeight: '700' },
});
