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
  textDetectionNativeAvailable,
} from '../../modules/memeget-bg';
import {
  createTextRegionLayers,
  canApplyTextRegionAction,
  flattenDetectedTextRegions,
  remapNormalizedRect,
  type DetectedTextResult,
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
      setSampleStatus('Border sampling is unavailable in this build. Choose a palette fill.');
      return;
    }
    const sourceRect = remapNormalizedRect(selectedRegion.rect, project.base, FULL_IMAGE_BASE);
    if (!sourceRect) {
      setSampleStatus('The selected region is outside the source image. Adjust the box to sample a fill.');
      return;
    }
    setSampleStatus('Sampling the nearby border color…');
    sampleImageBorderColor(sourceUri, sourceRect)
      .then((sample) => {
        if (sampleRequest.current !== request) return;
        if (!sample) {
          setSampleStatus('Border sampling is unavailable in this build. Choose a palette fill.');
          return;
        }
        if (!colorOverridden.current) setSelectedColor(sample.hex);
        setSampledRegionKey(currentRegionKey);
        setSampleStatus(`Sampled fill ${sample.hex} from ${sample.sampleCount} nearby opaque pixels.`);
      })
      .catch((error) => {
        if (sampleRequest.current === request) {
          setSampleStatus(`Could not sample the border color: ${String(error)}`);
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
  ]);

  return (
    <ScrollView
      contentContainerStyle={styles.root}
      keyboardShouldPersistTaps="handled"
      automaticallyAdjustKeyboardInsets
    >
      <Text style={styles.title}>Replace text</Text>
      <Text style={styles.copy}>
        Detect text on this device or draw a manual box. Cover uses a sampled solid fill, Pixelate adds a coarse mosaic, and Replace adds the cover plus editable text. These are explicit local editing layers; the original stays unchanged.
      </Text>

      {!textDetectionNativeAvailable && (
        <View style={styles.notice} accessibilityRole="text">
          <Text style={styles.noticeText}>On-device text detection is unavailable in this build. Manual region remains available.</Text>
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
          {detecting ? <ActivityIndicator color={colors.onVolt} /> : <Text style={styles.primaryActionText}>{detectError ? 'Retry detection' : 'Detect text'}</Text>}
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
          <Text style={styles.secondaryActionText}>{manualMode ? 'Drawing manual box…' : 'Manual region'}</Text>
        </PressableScale>
      </View>
      {!!detectError && <Text style={styles.error} accessibilityRole="alert">{detectError}</Text>}
      {!detecting && regions.length > 0 && (
        <Text style={styles.metric}>{regions.length} selectable text box{regions.length === 1 ? '' : 'es'} above the image.</Text>
      )}

      {selectedRegion ? (
        <View style={styles.selectionPanel}>
          <Text style={styles.selectionTitle}>Selected {selectedRegion.source} region</Text>
          <Text style={styles.metric}>
            {Math.round(selectedRegion.rect.width * 100)}% × {Math.round(selectedRegion.rect.height * 100)}% of visible image. Drag the box or its corner handle to adjust it before applying.
          </Text>
          <Text style={styles.fieldLabel}>Replacement text</Text>
          <TextInput
            value={replacementText}
            onChangeText={setReplacementText}
            editable={!disabled}
            multiline
            maxLength={20_000}
            style={styles.input}
            placeholder="Replacement text"
            placeholderTextColor={colors.faint}
            accessibilityLabel="Replacement text"
            accessibilityHint="Edit the text that Replace will add"
          />

          <Text style={styles.fieldLabel}>Solid cover fill</Text>
          <Text style={styles.metric}>{sampleStatus || 'Select a sampled or palette fill.'}</Text>
          <View style={styles.palette} accessibilityRole="radiogroup" accessibilityLabel="Cover fill colors">
            {COVER_COLORS.map((color) => (
              <PressableScale
                key={color}
                onPress={() => chooseColor(color)}
                disabled={disabled}
                style={[styles.swatch, { backgroundColor: color }, selectedColor === color && styles.swatchSelected]}
                accessibilityRole="radio"
                accessibilityLabel={`Use ${color} cover fill`}
                accessibilityState={{ checked: selectedColor === color, disabled: !!disabled }}
              >
                <Text style={[styles.swatchMark, color === '#000000' && styles.swatchMarkLight]}>{selectedColor === color ? 'Selected' : ''}</Text>
              </PressableScale>
            ))}
          </View>

          <View style={styles.pixelRow}>
            <Text style={styles.fieldLabel}>Pixel size {pixelSize}</Text>
            <View style={styles.stepper}>
              <PressableScale
                onPress={() => setPixelSize((value) => Math.max(1, value - 2))}
                disabled={disabled || pixelSize <= 1}
                style={styles.stepButton}
                accessibilityRole="button"
                accessibilityLabel="Decrease pixel size"
              ><Text style={styles.stepText}>−</Text></PressableScale>
              <PressableScale
                onPress={() => setPixelSize((value) => Math.min(256, value + 2))}
                disabled={disabled || pixelSize >= 256}
                style={styles.stepButton}
                accessibilityRole="button"
                accessibilityLabel="Increase pixel size"
              ><Text style={styles.stepText}>+</Text></PressableScale>
            </View>
          </View>

          <View style={styles.applyActions} accessibilityRole="toolbar" accessibilityLabel="Selected text region actions">
            {(['cover', 'pixelate', 'replace'] as const).map((action) => {
              const actionDisabled = !!disabled || !currentRegionKey || !canApplyTextRegionAction(
                action,
                currentRegionKey,
                sampledRegionKey,
                paletteRegionKey
              );
              return (
                <PressableScale
                  key={action}
                  onPress={() => apply(action)}
                  disabled={actionDisabled}
                  style={action === 'replace' ? styles.primaryAction : styles.secondaryAction}
                  accessibilityRole="button"
                  accessibilityLabel={action === 'cover' ? 'Cover selected region' : action === 'pixelate' ? 'Pixelate selected region' : 'Replace selected text'}
                  accessibilityHint={action === 'replace' ? 'Add a solid cover and editable text in one undo step' : `${action === 'cover' ? 'Add a sampled solid fill' : 'Add bounded pixelation'} in one undo step`}
                  accessibilityState={{ disabled: actionDisabled }}
                >
                  <Text style={action === 'replace' ? styles.primaryActionText : styles.secondaryActionText}>
                    {action === 'cover' ? 'Cover' : action === 'pixelate' ? 'Pixelate' : 'Replace'}
                  </Text>
                </PressableScale>
              );
            })}
          </View>
          {!!applyError && <Text style={styles.error} accessibilityRole="alert">{applyError}</Text>}
        </View>
      ) : (
        <Text style={styles.empty}>Tap a detected box above the image, or draw a manual rectangle.</Text>
      )}
    </ScrollView>
  );
});

const styles = StyleSheet.create({
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
