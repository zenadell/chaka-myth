import React, { useEffect, useState } from "react";
import { Pressable, StyleSheet, TextInput, View } from "react-native";
import { LinearGradient } from "expo-linear-gradient";
import { Feather } from "@expo/vector-icons";
import * as Haptics from "expo-haptics";
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from "react-native-reanimated";
import { colors, fonts, radius } from "../theme";

export function Composer(props: {
  value: string;
  onChangeText: (text: string) => void;
  onSend: () => void;
  onMicPress: () => void;
  onStop: () => void;
  busy: boolean;
  listening: boolean;
  sttAvailable: boolean;
}) {
  const { value, onChangeText, onSend, onMicPress, onStop, busy, listening, sttAvailable } = props;
  const canSend = value.trim().length > 0 && !busy;
  const [focused, setFocused] = useState(false);
  const reduced = useReducedMotion();

  // Pulsing ring while listening
  const ring = useSharedValue(0);
  useEffect(() => {
    if (listening && !reduced) {
      ring.value = 0;
      ring.value = withRepeat(
        withTiming(1, { duration: 1100, easing: Easing.out(Easing.quad) }),
        -1
      );
    } else {
      ring.value = withTiming(0, { duration: 200 });
    }
  }, [listening, reduced, ring]);

  const ringStyle = useAnimatedStyle(() => ({
    opacity: listening ? 0.7 - ring.value * 0.7 : 0,
    transform: [{ scale: 1 + ring.value * 0.55 }],
  }));

  const send = () => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    onSend();
  };

  const mic = () => {
    Haptics.selectionAsync();
    onMicPress();
  };

  return (
    <View style={styles.container}>
      <View
        style={[
          styles.inputWrap,
          focused && styles.inputWrapFocused,
          listening && styles.inputWrapListening,
        ]}
      >
        <TextInput
          style={styles.input}
          value={value}
          onChangeText={onChangeText}
          placeholder={listening ? "Listening…" : "Ask Chaka anything…"}
          placeholderTextColor={listening ? colors.primary : colors.textDim}
          multiline
          editable={!listening}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          onSubmitEditing={canSend ? send : undefined}
        />
      </View>

      <View>
        <Animated.View style={[styles.micRing, ringStyle]} pointerEvents="none" />
        <Pressable
          style={({ pressed }) => [
            styles.iconButton,
            listening && styles.micActive,
            pressed && styles.pressed,
          ]}
          onPress={mic}
          disabled={busy}
          accessibilityLabel={listening ? "Stop listening" : "Speak to Chaka"}
        >
          <Feather
            name={listening ? "square" : "mic"}
            size={19}
            color={listening ? colors.primary : sttAvailable ? colors.text : colors.textDim}
          />
        </Pressable>
      </View>

      {busy ? (
        <Pressable
          style={({ pressed }) => [pressed && styles.pressed]}
          onPress={onStop}
          accessibilityLabel="Stop"
        >
          <View style={[styles.iconButton, styles.stopButton]}>
            <Feather name="square" size={17} color={colors.primary} />
          </View>
        </Pressable>
      ) : (
        <Pressable
          style={({ pressed }) => [pressed && canSend && styles.pressed]}
          onPress={send}
          disabled={!canSend}
          accessibilityLabel="Send message"
        >
          {canSend ? (
            <LinearGradient
              colors={[colors.primaryBright, colors.primaryDeep]}
              start={{ x: 0, y: 0 }}
              end={{ x: 1, y: 1 }}
              style={styles.iconButton}
            >
              <Feather name="arrow-up" size={20} color={colors.onPrimary} />
            </LinearGradient>
          ) : (
            <View style={[styles.iconButton, styles.sendDisabled]}>
              <Feather name="arrow-up" size={20} color={colors.textDim} />
            </View>
          )}
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    alignItems: "flex-end",
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 8,
    backgroundColor: colors.bg,
  },
  inputWrap: {
    flex: 1,
    backgroundColor: colors.surface,
    borderRadius: radius.xl,
    borderWidth: 1.5,
    borderColor: colors.border,
  },
  inputWrapFocused: { borderColor: colors.borderBright },
  inputWrapListening: { borderColor: colors.primary },
  input: {
    minHeight: 45,
    maxHeight: 120,
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 12,
    color: colors.text,
    fontSize: 15.5,
    fontFamily: fonts.regular,
  },
  iconButton: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: "center",
    justifyContent: "center",
    overflow: "hidden",
  },
  micActive: {
    borderColor: colors.primary,
    backgroundColor: colors.primarySoft,
  },
  micRing: {
    position: "absolute",
    width: 48,
    height: 48,
    borderRadius: 24,
    borderWidth: 2,
    borderColor: colors.primary,
  },
  sendDisabled: { opacity: 0.7 },
  stopButton: { borderColor: colors.primary, backgroundColor: colors.primarySoft },
  pressed: { transform: [{ scale: 0.94 }] },
});
