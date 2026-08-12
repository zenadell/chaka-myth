import React, { useEffect } from "react";
import { StyleSheet, View } from "react-native";
import { LinearGradient } from "expo-linear-gradient";
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from "react-native-reanimated";
import { colors } from "../theme";

/**
 * The ember orb — Chaka's visual presence.
 * mode: "idle" slow breathing | "busy" fast pulse | "off" dim and still.
 */
export function Orb(props: { size?: number; mode: "idle" | "busy" | "off" }) {
  const { size = 120, mode } = props;
  const reduced = useReducedMotion();
  const pulse = useSharedValue(0);

  useEffect(() => {
    if (reduced || mode === "off") {
      pulse.value = withTiming(0, { duration: 300 });
      return;
    }
    const duration = mode === "busy" ? 700 : 2400;
    pulse.value = 0;
    pulse.value = withRepeat(
      withTiming(1, { duration, easing: Easing.inOut(Easing.sin) }),
      -1,
      true
    );
  }, [mode, reduced, pulse]);

  const haloStyle = useAnimatedStyle(() => ({
    transform: [{ scale: 1 + pulse.value * 0.18 }],
    opacity: 0.35 + pulse.value * 0.3,
  }));

  const coreStyle = useAnimatedStyle(() => ({
    transform: [{ scale: 1 + pulse.value * 0.05 }],
  }));

  const dim = mode === "off";

  return (
    <View style={{ width: size * 1.5, height: size * 1.5, alignItems: "center", justifyContent: "center" }}>
      <Animated.View
        style={[
          {
            position: "absolute",
            width: size * 1.4,
            height: size * 1.4,
            borderRadius: size,
            backgroundColor: dim ? colors.surfaceRaised : colors.primaryGlow,
          },
          haloStyle,
        ]}
      />
      <Animated.View style={coreStyle}>
        <LinearGradient
          colors={dim ? [colors.surfaceRaised, colors.surface] : [colors.primaryBright, colors.primaryDeep]}
          start={{ x: 0.2, y: 0 }}
          end={{ x: 0.8, y: 1 }}
          style={[styles.core, { width: size, height: size, borderRadius: size / 2 }]}
        >
          <View
            style={[
              styles.highlight,
              {
                width: size * 0.34,
                height: size * 0.34,
                borderRadius: size * 0.2,
                top: size * 0.14,
                left: size * 0.18,
                opacity: dim ? 0.06 : 0.35,
              },
            ]}
          />
        </LinearGradient>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  core: { overflow: "hidden" },
  highlight: { position: "absolute", backgroundColor: "#FFFFFF" },
});
