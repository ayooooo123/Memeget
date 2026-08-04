import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import {
  borderColorSamplerNativeAvailable,
  detectTextRegions,
  sampleImageBorderColor,
  sampleImagePixelGrid,
  textDetectionNativeAvailable,
} from '../../modules/memeget-bg';
import {
  createTextRegionLayers,
  canApplyTextRegionAction,
  flattenDetectedTextRegions,
  inferOriginalTextStyle,
  remapNormalizedRect,
  type DetectedTextResult,
  type InferredTextStyle,
  type TextRegionAction,
  type TextRegionCandidate,
  textRegionFingerprint,
} from '../memeImageEditCore';
import { PROJECT_LIMITS, type BaseTransform, type MemeEditLayer, type MemeEditProject } from '../memeEditProjectCore';
import { colors, radius, space, type } from '../theme';
import { PressableScale } from './ui';

const COVER_COLORS = ['#000000', '#FFFFFF', '#20242A', '#E9E5DC', '#FF4E42', '#B8FF2C'] as const;

const FULL_IMAGE_BASE: BaseTransform = {
  rotation: 0,
  flipX: false,
  flipY: false,
  crop: { x: 0, y: 0, width: 1, height: 1 },
  outputAspect: 'source',
};

function nextLayerId(
  prefix: string,
  kind: 'cover' | 'text',
  project: MemeEditProject,
  reservedIds: readonly string[] = []
): string {
  const ids = new Set([...project.layers.map((layer) => layer.id), ...reservedIds]);
  let suffix = project.layers.length + 1;
  let id = `${prefix}-${kind}-${suffix}`;
  while (ids.has(id)) {
    suffix += 1;
    id = `${prefix}-${kind}-${suffix}`;
  }
  return id;
}

export const MemeTextReplaceTool = React.memo(function MemeTextReplaceTool({
  project,
  sourceUri,
  idPrefix,
  regions,
  selectedRegion,
  manualMode,
  disabled,
  onRegionsChange,
  onSelectedRegionChange,
  onManualModeChange,
  onAddLayers,
  onSelectLayer,
}: {
  project: MemeEditProject;
  sourceUri: string;
  idPrefix: string;
  regions: TextRegionCandidate[];
  selectedRegion: TextRegionCandidate | null;
  manualMode: boolean;
  disabled?: boolean;
  onRegionsChange: (regions: TextRegionCandidate[]) => void;
  onSelectedRegionChange: (region: TextRegionCandidate | null) => void;
  onManualModeChange: (enabled: boolean) => void;
  onAddLayers: (layers: MemeEditLayer[]) => void;
  onSelectLayer: (id: string | null) => void;
}) {
  const [detecting, setDetecting] = useState(false);
  const [detectError, setDetectError] = useState('');
  const [sampleStatus, setSampleStatus] = useState('');
  const [selectedColor, setSelectedColor] = useState<string>('#000000');
  const [replacementText, setReplacementText] = useState('');
  const [pixelSize, setPixelSize] = useState(12);
  const [applyError, setApplyError] = useState('');
  // The look of the text being replaced. Sampled, never assumed — null means we
  // genuinely could not read it, which the UI says out loud.
  const [inferredStyle, setInferredStyle] = useState<InferredTextStyle | null>(null);
  const [showAdjust, setShowAdjust] = useState(false);
  const [sampledRegionKey, setSampledRegionKey] = useState<string | null>(null);
  const [paletteRegionKey, setPaletteRegionKey] = useState<string | null>(null);
  const detectionRequest = useRef(0);
  const sampleRequest = useRef(0);
  const colorOverridden = useRef(false);
  const currentRegionKey = selectedRegion ? textRegionFingerprint(selectedRegion) : null;

  useEffect(() => () => {
    detectionRequest.current += 1;
    sampleRequest.current += 1;
  }, []);

  useEffect(() => {
    setReplacementText(selectedRegion?.text ?? '');
    setApplyError('');
    colorOverridden.current = false;
  }, [selectedRegion?.id]);

  useEffect(() => {
    const request = sampleRequest.current + 1;
    sampleRequest.current = request;
    setSampledRegionKey(null);
    setPaletteRegionKey(null);
    if (!selectedRegion) {
      setSampleStatus('');
      return;
    }
    if (!borderColorSamplerNativeAvailable) {
      setSampleStatus('');
      setPaletteRegionKey(currentRegionKey);
      return;
    }
    const sourceRect = remapNormalizedRect(selectedRegion.rect, project.base, FULL_IMAGE_BASE);
    if (!sourceRect) {
      setSampleStatus('That box is outside the image — drag it back over the picture.');
      return;
    }
    setSampleStatus('Matching the original text…');

    // Two samples, two jobs. The border colour is what the cover is painted
    // with; the pixel grid INSIDE the box is what tells us how the original
    // text looked, so the replacement can keep that look instead of arriving as
    // a default caption.
    sampleImageBorderColor(sourceUri, sourceRect)
      .then((sample) => {
        if (sampleRequest.current !== request) return;
        if (!sample) {
          // Claim the default for this region so the action is never blocked
          // with no way for the user to discover why.
          setPaletteRegionKey(currentRegionKey);
          return;
        }
        if (!colorOverridden.current) setSelectedColor(sample.hex);
        setSampledRegionKey(currentRegionKey);
      })
      .catch(() => {
        if (sampleRequest.current === request) setPaletteRegionKey(currentRegionKey);
      });

    sampleImagePixelGrid(sourceUri, sourceRect, 8)
      .then((grid) => {
        if (sampleRequest.current !== request) return;
        if (!grid) {
          setInferredStyle(null);
          setSampleStatus('');
          setPaletteRegionKey((key) => key ?? currentRegionKey);
          return;
        }
        const style = inferOriginalTextStyle({
          sampledColors: grid.colors,
          originalText: selectedRegion.text,
          regionHeight: selectedRegion.rect.height,
          lineCount: Math.max(1, selectedRegion.text.split('\n').length),
        });
        // Only claim a match when the glyphs were genuinely separable. Below
        // that the numbers are noise and pretending otherwise would restyle the
        // meme in a way the user did not ask for.
        const readable = style.contrast >= 1.6;
        setInferredStyle(readable ? style : null);
        setSampleStatus(readable ? `Matching the original text — ${style.color}` : '');
      })
      .catch(() => {
        if (sampleRequest.current === request) {
          setInferredStyle(null);
          setSampleStatus('');
        }
      });
  }, [
    project.base,
    selectedRegion?.id,
    selectedRegion?.rect.height,
    selectedRegion?.rect.width,
    selectedRegion?.rect.x,
    selectedRegion?.rect.y,
    sourceUri,
    currentRegionKey,
  ]);

  const detect = useCallback(() => {
    if (disabled || !textDetectionNativeAvailable || detecting) return;
    const request = detectionRequest.current + 1;
    detectionRequest.current = request;
    setDetecting(true);
    setDetectError('');
    onSelectedRegionChange(null);
    detectTextRegions(sourceUri)
      .then((result) => {
        if (detectionRequest.current !== request) return;
        if (!result) {
          setDetectError('Text detection is unavailable in this build. Draw a manual region instead.');
          return;
        }
        const nextRegions = flattenDetectedTextRegions(result as DetectedTextResult).flatMap((region) => {
          const rect = remapNormalizedRect(region.rect, FULL_IMAGE_BASE, project.base);
          return rect ? [{ ...region, rect }] : [];
        });
        onRegionsChange(nextRegions);
        if (nextRegions.length === 0) {
          setDetectError('No text boxes were detected. Draw a manual region for missed text.');
        }
      })
      .catch((error) => {
        if (detectionRequest.current === request) {
          setDetectError(`Text detection failed: ${String(error)}`);
        }
      })
      .finally(() => {
        if (detectionRequest.current === request) setDetecting(false);
      });
  }, [
    detecting,
    disabled,
    onRegionsChange,
    onSelectedRegionChange,
    project.base,
    sourceUri,
  ]);

  // Opening the tool and being told to press "Detect text" is a step with no
  // decision in it. Run it once automatically; the button stays for retries.
  const autoDetected = useRef(false);
  useEffect(() => {
    if (autoDetected.current || disabled || detecting) return;
    if (!textDetectionNativeAvailable || regions.length > 0) return;
    autoDetected.current = true;
    detect();
  }, [detect, detecting, disabled, regions.length]);

  const chooseColor = useCallback((color: string) => {
    colorOverridden.current = true;
    if (currentRegionKey) setPaletteRegionKey(currentRegionKey);
    setSelectedColor(color);
    setSampleStatus(`Palette fill ${color} selected.`);
  }, [currentRegionKey]);

  const apply = useCallback((action: TextRegionAction) => {
    if (!selectedRegion || disabled) return;
    if (!currentRegionKey || !canApplyTextRegionAction(
      action,
      currentRegionKey,
      sampledRegionKey,
      paletteRegionKey
    )) {
      setApplyError('Wait for the current sampled fill, or choose a palette fill for this region.');
      return;
    }
    const requiredSlots = action === 'replace' ? 2 : 1;
    if (project.layers.length + requiredSlots > PROJECT_LIMITS.maxLayers) {
      setApplyError(
        `${action === 'replace' ? 'Replace needs two layer slots' : 'This action needs one layer slot'}, but the project has the ${PROJECT_LIMITS.maxLayers} layer maximum.`
      );
      return;
    }
    const coverId = nextLayerId(idPrefix, 'cover', project);
    const textId = nextLayerId(idPrefix, 'text', project, [coverId]);
    const layers = createTextRegionLayers({
      action,
      rect: selectedRegion.rect,
      text: replacementText || selectedRegion.text,
      coverId,
      textId,
      color: selectedColor,
      pixelSize,
      inferredStyle,
    });
    onAddLayers(layers);
    onSelectLayer(layers[layers.length - 1].id);
    setApplyError('');
  }, [
    disabled,
    idPrefix,
    onAddLayers,
    currentRegionKey,
    pixelSize,
    project,
    paletteRegionKey,
    sampledRegionKey,
    replacementText,
    selectedColor,
    selectedRegion,
    inferredStyle,
  ]);

  return (
    <ScrollView
      contentContainerStyle={styles.root}
      keyboardShouldPersistTaps="handled"
      automaticallyAdjustKeyboardInsets
    >
      <Text style={styles.title}>Replace text</Text>
      <Text style={styles.copy}>
        Tap any text on the image, then type what it should say instead. The new text keeps the original's look. Your original file is never changed.
      </Text>

      {!textDetectionNativeAvailable && (
        <View style={styles.notice} accessibilityRole="text">
          <Text style={styles.noticeText}>This build cannot find text automatically. Draw a box around the text instead.</Text>
        </View>
      )}
      <View style={styles.topActions}>
        <PressableScale
          onPress={detect}
          disabled={disabled || detecting || !textDetectionNativeAvailable}
          style={styles.primaryAction}
          accessibilityRole="button"
          accessibilityLabel={detectError ? 'Retry text detection' : 'Detect text'}
          accessibilityHint="Find real text boxes in the local image on this device"
          accessibilityState={{ busy: detecting, disabled: !!disabled || detecting || !textDetectionNativeAvailable }}
        >
          {detecting ? <ActivityIndicator color={colors.onVolt} /> : <Text style={styles.primaryActionText}>{detectError ? 'Try again' : 'Find text'}</Text>}
        </PressableScale>
        <PressableScale
          onPress={() => onManualModeChange(!manualMode)}
          disabled={disabled}
          style={[styles.secondaryAction, manualMode && styles.selectedAction]}
          accessibilityRole="button"
          accessibilityLabel="Draw manual text region"
          accessibilityHint="Draw a box over text that detection missed"
          accessibilityState={{ selected: manualMode, disabled: !!disabled }}
        >
          <Text style={styles.secondaryActionText}>{manualMode ? 'Draw a box…' : 'Draw a box'}</Text>
        </PressableScale>
      </View>
      {!!detectError && <Text style={styles.error} accessibilityRole="alert">{detectError}</Text>}
      {!detecting && regions.length > 0 && (
        <Text style={styles.metric}>Found {regions.length} piece{regions.length === 1 ? '' : 's'} of text — tap one on the image.</Text>
      )}

      {selectedRegion ? (
        <View style={styles.selectionPanel}>
          {/* The text field IS the feature. It opens pre-filled with what the
              meme actually says, so the interaction is "edit this text", not
              "compose a replacement and then reason about cover fills". */}
          <Text style={styles.fieldLabel}>Change this text</Text>
          <TextInput
            value={replacementText}
            onChangeText={setReplacementText}
            editable={!disabled}
            multiline
            maxLength={20_000}
            style={styles.input}
            placeholder="Type the new text"
            placeholderTextColor={colors.faint}
            accessibilityLabel="New text"
            accessibilityHint="Replaces the text you tapped, keeping its original style"
          />
          <Text style={styles.metric}>
            {inferredStyle
              ? `Keeping the original style — ${inferredStyle.preset === 'impact' ? 'bold outlined caption' : inferredStyle.uppercase ? 'caps' : 'plain text'}, sampled colour.`
              : 'The original style could not be read here, so a plain caption is used.'}
          </Text>

          <PressableScale
            onPress={() => apply('replace')}
            disabled={!!disabled || !replacementText.trim()}
            style={styles.primaryAction}
            accessibilityRole="button"
            accessibilityLabel="Replace this text"
            accessibilityHint="Covers the original and adds your text in the same style, in one undo step"
            accessibilityState={{ disabled: !!disabled || !replacementText.trim() }}
          >
            <Text style={styles.primaryActionText}>Replace this text</Text>
          </PressableScale>

          {/* Everything below is refinement. It used to be mandatory — the
              Replace button stayed disabled until a fill was chosen, with no
              explanation — which is what made this tool feel broken. */}
          <PressableScale
            onPress={() => setShowAdjust((value) => !value)}
            style={styles.disclosure}
            accessibilityRole="button"
            accessibilityLabel={showAdjust ? 'Hide options' : 'More options'}
            accessibilityState={{ expanded: showAdjust }}
          >
            <Text style={styles.disclosureText}>{showAdjust ? 'Fewer options ▴' : 'More options ▾'}</Text>
          </PressableScale>

          {showAdjust ? (
            <>
              <Text style={styles.metric}>
                {Math.round(selectedRegion.rect.width * 100)}% × {Math.round(selectedRegion.rect.height * 100)}% of the image. Drag the box or its corner handle to adjust it.
              </Text>
              <Text style={styles.fieldLabel}>Patch colour</Text>
              <Text style={styles.metric}>{sampleStatus || 'Sampled from the image.'}</Text>
              <View style={styles.palette} accessibilityRole="radiogroup" accessibilityLabel="Patch colour">
                {COVER_COLORS.map((color) => (
                  <PressableScale
                    key={color}
                    onPress={() => chooseColor(color)}
                    disabled={disabled}
                    style={[styles.swatch, { backgroundColor: color }, selectedColor === color && styles.swatchSelected]}
                    accessibilityRole="radio"
                    accessibilityLabel={`Use ${color}`}
                    accessibilityState={{ checked: selectedColor === color, disabled: !!disabled }}
                  />
                ))}
              </View>

              <View style={styles.pixelRow}>
                <Text style={styles.fieldLabel}>Blur size {pixelSize}</Text>
                <View style={styles.stepper}>
                  <PressableScale
                    onPress={() => setPixelSize((value) => Math.max(1, value - 2))}
                    disabled={disabled || pixelSize <= 1}
                    style={styles.stepButton}
                    accessibilityRole="button"
                    accessibilityLabel="Decrease blur size"
                  ><Text style={styles.stepText}>−</Text></PressableScale>
                  <PressableScale
                    onPress={() => setPixelSize((value) => Math.min(256, value + 2))}
                    disabled={disabled || pixelSize >= 256}
                    style={styles.stepButton}
                    accessibilityRole="button"
                    accessibilityLabel="Increase blur size"
                  ><Text style={styles.stepText}>+</Text></PressableScale>
                </View>
              </View>

              <View style={styles.applyActions} accessibilityRole="toolbar" accessibilityLabel="Other ways to remove this text">
                <PressableScale
                  onPress={() => apply('cover')}
                  disabled={!!disabled}
                  style={styles.secondaryAction}
                  accessibilityRole="button"
                  accessibilityLabel="Just hide the text"
                  accessibilityHint="Paints over it with the patch colour and adds no new text"
                >
                  <Text style={styles.secondaryActionText}>Just hide it</Text>
                </PressableScale>
                <PressableScale
                  onPress={() => apply('pixelate')}
                  disabled={!!disabled}
                  style={styles.secondaryAction}
                  accessibilityRole="button"
                  accessibilityLabel="Pixelate the text"
                  accessibilityHint="Covers it with a coarse mosaic instead of a solid patch"
                >
                  <Text style={styles.secondaryActionText}>Pixelate</Text>
                </PressableScale>
              </View>
            </>
          ) : null}
          {!!applyError && <Text style={styles.error} accessibilityRole="alert">{applyError}</Text>}
        </View>
      ) : (
        <Text style={styles.empty}>Tap any text on the image above to change it.</Text>
      )}
    </ScrollView>
  );
});

const styles = StyleSheet.create({
  disclosure: { paddingVertical: space.xs, alignItems: 'center' },
  disclosureText: { ...type.caption, color: colors.muted },
  root: { padding: space.md, gap: space.sm, paddingBottom: space.xxl },
  title: { ...type.title, color: colors.text },
  copy: { ...type.body, color: colors.textDim },
  notice: { padding: space.sm, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface2 },
  noticeText: { ...type.caption, color: colors.textDim },
  topActions: { gap: space.xs },
  primaryAction: { minHeight: 44, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', paddingHorizontal: space.md, backgroundColor: colors.volt },
  primaryActionText: { ...type.body, color: colors.onVolt, fontWeight: '900' },
  secondaryAction: { minHeight: 44, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', paddingHorizontal: space.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface2 },
  secondaryActionText: { ...type.body, color: colors.text, fontWeight: '700' },
  selectedAction: { borderColor: colors.volt, backgroundColor: colors.voltDim },
  selectionPanel: { gap: space.sm, marginTop: space.sm },
  selectionTitle: { ...type.title, color: colors.text },
  fieldLabel: { ...type.micro, color: colors.textDim, textTransform: 'uppercase' },
  input: { minHeight: 88, maxHeight: 180, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface2, color: colors.text, padding: space.sm, textAlignVertical: 'top', ...type.body },
  palette: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  swatch: { width: 52, height: 52, borderRadius: radius.md, borderWidth: 2, borderColor: colors.border, alignItems: 'center', justifyContent: 'center' },
  swatchSelected: { borderColor: colors.volt, borderWidth: 3 },
  swatchMark: { ...type.micro, color: colors.bg, fontSize: 8, fontWeight: '900' },
  swatchMarkLight: { color: colors.text },
  pixelRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  stepper: { flexDirection: 'row', gap: space.xs },
  stepButton: { width: 44, height: 44, borderRadius: 22, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface2, alignItems: 'center', justifyContent: 'center' },
  stepText: { ...type.title, color: colors.text },
  applyActions: { gap: space.xs },
  metric: { ...type.caption, color: colors.textDim },
  empty: { ...type.body, color: colors.textDim, paddingVertical: space.lg, textAlign: 'center' },
  error: { ...type.caption, color: colors.danger },
});
