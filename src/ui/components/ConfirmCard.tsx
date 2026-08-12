import React from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { LinearGradient } from "expo-linear-gradient";
import { Feather } from "@expo/vector-icons";
import * as Haptics from "expo-haptics";
import Animated, { SlideInDown } from "react-native-reanimated";
import { colors, fonts, radius } from "../theme";

export function ConfirmCard(props: {
  summary: string;
  onApprove: () => void;
  onDecline: () => void;
  onAlways?: () => void;
}) {
  const approve = () => {
    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    props.onApprove();
  };
  const decline = () => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    props.onDecline();
  };
  const always = () => {
    Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    props.onAlways?.();
  };

  return (
    <Animated.View entering={SlideInDown.springify().damping(19)} style={styles.card}>
      <View style={styles.titleRow}>
        <Feather name="shield" size={13} color={colors.primary} />
        <Text style={styles.title}>Chaka wants to</Text>
      </View>
      <Text style={styles.summary}>{props.summary}</Text>
      <View style={styles.buttons}>
        <Pressable
          style={({ pressed }) => [styles.button, styles.decline, pressed && styles.pressed]}
          onPress={decline}
        >
          <Text style={styles.declineText}>Decline</Text>
        </Pressable>
        <Pressable
          style={({ pressed }) => [styles.buttonWrap, pressed && styles.pressed]}
          onPress={approve}
        >
          <LinearGradient
            colors={[colors.primaryBright, colors.primaryDeep]}
            start={{ x: 0, y: 0 }}
            end={{ x: 1, y: 1 }}
            style={[styles.button, styles.approve]}
          >
            <Text style={styles.approveText}>Just once</Text>
          </LinearGradient>
        </Pressable>
      </View>
      {props.onAlways && (
        <Pressable
          style={({ pressed }) => [styles.alwaysRow, pressed && styles.pressed]}
          onPress={always}
        >
          <Feather name="check-circle" size={13} color={colors.textDim} />
          <Text style={styles.alwaysText}>Always allow screen control (don't ask again)</Text>
        </Pressable>
      )}
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  card: {
    marginHorizontal: 16,
    marginVertical: 8,
    backgroundColor: colors.surfaceRaised,
    borderColor: colors.primaryGlow,
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: 16,
    shadowColor: colors.primary,
    shadowOpacity: 0.25,
    shadowRadius: 16,
    shadowOffset: { width: 0, height: 4 },
    elevation: 8,
  },
  titleRow: { flexDirection: "row", alignItems: "center", gap: 6, marginBottom: 6 },
  title: {
    color: colors.textDim,
    fontSize: 11.5,
    textTransform: "uppercase",
    letterSpacing: 1.4,
    fontFamily: fonts.semibold,
  },
  summary: { color: colors.text, fontSize: 15.5, lineHeight: 22, marginBottom: 14, fontFamily: fonts.regular },
  buttons: { flexDirection: "row", gap: 10 },
  buttonWrap: { flex: 1 },
  button: {
    borderRadius: radius.md,
    paddingVertical: 12,
    alignItems: "center",
  },
  pressed: { transform: [{ scale: 0.97 }] },
  approve: { width: "100%" },
  approveText: { color: colors.onPrimary, fontFamily: fonts.bold, fontSize: 15 },
  decline: {
    flex: 1,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.borderBright,
  },
  declineText: { color: colors.text, fontFamily: fonts.semibold, fontSize: 15 },
  alwaysRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 6,
    marginTop: 12,
    paddingVertical: 6,
  },
  alwaysText: { color: colors.textDim, fontFamily: fonts.medium, fontSize: 12.5 },
});
