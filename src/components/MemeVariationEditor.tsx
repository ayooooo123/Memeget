import React, { useEffect, useState } from 'react';
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { Image } from 'expo-image';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { colors, radius, space } from '../theme';
import type { MemeRecord } from '../types';

type EditorItem = Pick<MemeRecord, 'id' | 'kind' | 'name' | 'uri' | 'thumbUri'>;

export interface VariationDraft {
  topText: string;
  bottomText: string;
  coverTop: boolean;
  coverBottom: boolean;
}

export function MemeVariationEditor({
  item,
  visible,
  saving,
  error,
  onClose,
  onSave,
}: {
  item: EditorItem | null;
  visible: boolean;
  saving: boolean;
  onClose: () => void;
  error: string;
  onSave: (draft: VariationDraft) => void;
}) {
  const insets = useSafeAreaInsets();
  const [topText, setTopText] = useState('');
  const [bottomText, setBottomText] = useState('');
  const [coverTop, setCoverTop] = useState(false);
  const [coverBottom, setCoverBottom] = useState(false);

  useEffect(() => {
    if (!visible) return;
    setTopText('');
    setBottomText('');
    setCoverTop(false);
    setCoverBottom(false);
  }, [visible, item?.id]);

  const isVideo = item?.kind === 'video';
  const hasChange = !!topText.trim() || !!bottomText.trim() || (!isVideo && (coverTop || coverBottom));

  return (
    <Modal visible={visible && !!item} animationType="slide" statusBarTranslucent onRequestClose={onClose}>
      {item && (
        <KeyboardAvoidingView
          style={styles.root}
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
          keyboardVerticalOffset={0}
        >
          <View style={[styles.header, { paddingTop: insets.top + space.sm }]}>
            <Pressable onPress={onClose} hitSlop={12} disabled={saving}>
              <Text style={styles.cancel}>Cancel</Text>
            </Pressable>
            <View style={styles.headingWrap}>
              <Text style={styles.heading}>Create variation</Text>
              <Text style={styles.fileName} numberOfLines={1}>{item.name}</Text>
            </View>
            <Pressable
              onPress={() => onSave({ topText, bottomText, coverTop, coverBottom })}
              disabled={!hasChange || saving}
              style={[styles.save, (!hasChange || saving) && styles.disabled]}
              accessibilityRole="button"
              accessibilityLabel="Save new variation"
            >
              <Text style={styles.saveText}>{saving ? 'Rendering…' : 'Save new'}</Text>
            </Pressable>
          </View>

          <ScrollView
            style={styles.scroll}
            contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + space.xl }]}
            keyboardShouldPersistTaps="handled"
          >
            <View style={styles.preview}>
              <Image
                source={{ uri: isVideo ? item.thumbUri || item.uri : item.uri }}
                style={styles.previewImage}
                contentFit="contain"
                cachePolicy="none"
              />
              {!!topText.trim() && <Text style={[styles.previewCaption, styles.previewTop]}>{topText}</Text>}
              {!!bottomText.trim() && (
                <Text style={[styles.previewCaption, styles.previewBottom]}>{bottomText}</Text>
              )}
            </View>
            {!!error && <Text style={styles.error}>{error}</Text>}

            <View style={styles.form}>
              <Text style={styles.label}>Top text</Text>
              <TextInput
                style={styles.input}
                value={topText}
                onChangeText={setTopText}
                placeholder="Add a caption at the top"
                placeholderTextColor={colors.faint}
                multiline
                maxLength={180}
              />
              {!isVideo && (
                <ToggleRow
                  label="Cover the original top text"
                  active={coverTop}
                  onPress={() => setCoverTop((value) => !value)}
                />
              )}

              <Text style={styles.label}>Bottom text</Text>
              <TextInput
                style={styles.input}
                value={bottomText}
                onChangeText={setBottomText}
                placeholder="Add a caption at the bottom"
                placeholderTextColor={colors.faint}
                multiline
                maxLength={180}
              />
              {!isVideo && (
                <ToggleRow
                  label="Cover the original bottom text"
                  active={coverBottom}
                  onPress={() => setCoverBottom((value) => !value)}
                />
              )}

              <Text style={styles.note}>
                {isVideo
                  ? 'Text is burned into a new MP4. The original video stays unchanged.'
                  : 'Cover uses a dark strip to remove existing edge text. A new PNG is saved; the original stays unchanged.'}
              </Text>
            </View>
          </ScrollView>
        </KeyboardAvoidingView>
      )}
    </Modal>
  );
}

function ToggleRow({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <Pressable style={styles.toggleRow} onPress={onPress} accessibilityRole="checkbox" accessibilityState={{ checked: active }}>
      <View style={[styles.check, active && styles.checkActive]}>
        {active && <Text style={styles.checkMark}>✓</Text>}
      </View>
      <Text style={styles.toggleLabel}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  header: {
    minHeight: 70,
    paddingHorizontal: space.lg,
    paddingBottom: space.sm,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  cancel: { color: colors.muted, fontSize: 14, fontWeight: '600' },
  headingWrap: { flex: 1, alignItems: 'center' },
  heading: { color: colors.text, fontSize: 16, fontWeight: '800' },
  fileName: { color: colors.faint, fontSize: 10, maxWidth: 180 },
  save: { backgroundColor: colors.volt, paddingHorizontal: 12, paddingVertical: 9, borderRadius: radius.md },
  saveText: { color: colors.bg, fontSize: 12, fontWeight: '800' },
  disabled: { opacity: 0.4 },
  scroll: { flex: 1 },
  content: { padding: space.lg, gap: space.lg },
  preview: {
    height: 310,
    overflow: 'hidden',
    backgroundColor: '#000',
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
  },
  previewImage: { width: '100%', height: '100%' },
  previewCaption: {
    position: 'absolute',
    left: '5%',
    width: '90%',
    color: '#fff',
    textAlign: 'center',
    fontSize: 24,
    lineHeight: 27,
    fontWeight: '900',
    textTransform: 'uppercase',
    textShadowColor: '#000',
    textShadowOffset: { width: 2, height: 2 },
    textShadowRadius: 2,
    backgroundColor: 'rgba(0,0,0,0.35)',
  },
  previewTop: { top: 12 },
  previewBottom: { bottom: 12 },
  form: { gap: space.sm },
  label: { color: colors.textDim, fontSize: 12, fontWeight: '800', marginTop: space.sm },
  input: {
    minHeight: 54,
    color: colors.text,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: space.md,
    paddingVertical: 11,
    fontSize: 15,
    textAlignVertical: 'top',
  },
  toggleRow: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 7 },
  check: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkActive: { backgroundColor: colors.volt, borderColor: colors.volt },
  checkMark: { color: colors.bg, fontSize: 13, fontWeight: '900' },
  toggleLabel: { color: colors.text, fontSize: 13 },
  error: { color: colors.danger, fontSize: 12, lineHeight: 17 },
  note: { color: colors.muted, fontSize: 12, lineHeight: 17, marginTop: space.sm },
});
