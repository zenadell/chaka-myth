import React from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { Feather } from "@expo/vector-icons";
import Animated, { FadeInLeft } from "react-native-reanimated";
import { colors, fonts, radius } from "../theme";

const STATUS_ICON: Record<string, { name: keyof typeof Feather.glyphMap; color: string }> = {
  ok: { name: "check", color: colors.success },
  error: { name: "x", color: colors.danger },
  declined: { name: "slash", color: colors.textDim },
};

export function ToolChip(props: {
  summary: string;
  status: "running" | "ok" | "error" | "declined";
}) {
  const { summary, status } = props;
  const icon = STATUS_ICON[status];

  return (
    <Animated.View entering={FadeInLeft.springify().damping(20)} style={styles.row}>
      <View style={[styles.chip, status === "running" && styles.chipRunning]}>
        {status === "running" ? (
          <ActivityIndicator size="small" color={colors.primary} />
        ) : (
          <Feather name={icon.name} size={13} color={icon.color} />
        )}
        <Text style={styles.label} numberOfLines={1}>
          {summary}
          {status === "declined" ? " (declined)" : ""}
        </Text>
      </View>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  row: { paddingHorizontal: 16, marginVertical: 3 },
  chip: {
    flexDirection: "row",
    alignItems: "center",
    alignSelf: "flex-start",
    backgroundColor: colors.surfaceRaised,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.full,
    paddingHorizontal: 12,
    paddingVertical: 7,
    gap: 8,
    maxWidth: "85%",
  },
  chipRunning: { borderColor: colors.primaryGlow },
  label: { color: colors.textSecondary, fontSize: 12.5, flexShrink: 1, fontFamily: fonts.medium },
});
