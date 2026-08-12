import React from "react";
import { StyleSheet, Text, View } from "react-native";
import { LinearGradient } from "expo-linear-gradient";
import Animated, { FadeInDown, FadeInUp } from "react-native-reanimated";
import { colors, fonts, radius } from "../theme";

export function MessageBubble(props: {
  role: "user" | "assistant" | "error";
  text: string;
  streaming?: boolean;
}) {
  const { role, text, streaming } = props;
  const isUser = role === "user";
  const isError = role === "error";

  if (isUser) {
    return (
      <Animated.View
        entering={FadeInUp.springify().damping(18).stiffness(180)}
        style={[styles.row, styles.rowUser]}
      >
        <LinearGradient
          colors={[colors.userBubbleFrom, colors.userBubbleTo]}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={[styles.bubble, styles.userBubble]}
        >
          <Text style={styles.userText}>{text}</Text>
        </LinearGradient>
      </Animated.View>
    );
  }

  return (
    <Animated.View
      entering={FadeInDown.springify().damping(18).stiffness(180)}
      style={styles.row}
    >
      <View style={[styles.bubble, styles.assistantBubble, isError && styles.errorBubble]}>
        <Text style={[styles.text, isError && styles.errorText]}>
          {text}
          {streaming ? " ▍" : ""}
        </Text>
      </View>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", marginVertical: 5, paddingHorizontal: 16 },
  rowUser: { justifyContent: "flex-end" },
  bubble: {
    maxWidth: "86%",
    borderRadius: radius.lg,
    paddingHorizontal: 15,
    paddingVertical: 11,
  },
  userBubble: {
    borderBottomRightRadius: 6,
    shadowColor: colors.primary,
    shadowOpacity: 0.35,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
    elevation: 6,
  },
  assistantBubble: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderBottomLeftRadius: 6,
  },
  errorBubble: {
    backgroundColor: colors.dangerSoft,
    borderColor: colors.danger,
  },
  text: { color: colors.text, fontSize: 15.5, lineHeight: 23, fontFamily: fonts.regular },
  userText: { color: colors.onPrimary, fontSize: 15.5, lineHeight: 23, fontFamily: fonts.medium },
  errorText: { color: colors.danger },
});
