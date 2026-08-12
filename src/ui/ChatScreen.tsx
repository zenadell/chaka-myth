import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  FlatList,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Feather } from "@expo/vector-icons";
import * as Haptics from "expo-haptics";
import Animated, { FadeIn, FadeInDown } from "react-native-reanimated";
import { useChat, DisplayItem } from "../state/chatStore";
import { useSettings } from "../state/settings";
import { MessageBubble } from "./components/MessageBubble";
import { ToolChip } from "./components/ToolChip";
import { ConfirmCard } from "./components/ConfirmCard";
import { Composer } from "./components/Composer";
import { TypingDots } from "./components/TypingDots";
import { Orb } from "./components/Orb";
import { SettingsSheet } from "./SettingsSheet";
import { ActivityLog } from "./ActivityLog";
import { startListening, sttAvailable, SttSession } from "../voice/stt";
import { startWakeword, stopWakeword, wakewordAvailable } from "../voice/wakeword";
import { stopSpeaking } from "../voice/tts";
import { colors, fonts } from "./theme";

const SUGGESTIONS = [
  "What's the weather right now?",
  "Open YouTube and search lo-fi beats",
  "Find my photos from last week",
  "Remind me to stretch in 30 minutes",
];

export function ChatScreen() {
  const insets = useSafeAreaInsets();
  const { items, busy, pendingConfirmation, sendMessage, resolveConfirmation, stop } = useChat();
  const apiKey = useSettings((s) => s.apiKey);

  const [draft, setDraft] = useState("");
  const [listening, setListening] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [activityOpen, setActivityOpen] = useState(false);
  const [wakewordOn, setWakewordOn] = useState(false);
  const sessionRef = useRef<SttSession | null>(null);
  const listRef = useRef<FlatList>(null);

  const beginListening = useCallback(async () => {
    if (listening || busy) return;
    stopSpeaking();
    setListening(true);
    const session = await startListening({
      onPartial: (text) => setDraft(text),
      onFinal: (text) => {
        setListening(false);
        sessionRef.current = null;
        if (text && text.trim()) {
          setDraft("");
          sendMessage(text);
        }
      },
      onError: (message) => {
        setListening(false);
        sessionRef.current = null;
        setDraft("");
        console.warn("STT:", message);
      },
    });
    if (session) sessionRef.current = session;
    else setListening(false);
  }, [listening, busy, sendMessage]);

  // Always-on "Hey Chaka" wake word (activates once Porcupine is configured)
  useEffect(() => {
    let mounted = true;
    if (wakewordAvailable()) {
      startWakeword(() => {
        if (!mounted) return;
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        beginListening();
      }).then((ok) => mounted && setWakewordOn(ok));
    }
    return () => {
      mounted = false;
      stopWakeword();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (items.length > 0) {
      setTimeout(() => listRef.current?.scrollToEnd({ animated: true }), 50);
    }
  }, [items.length]);

  const onMicPress = () => {
    if (listening) {
      sessionRef.current?.stop();
    } else {
      beginListening();
    }
  };

  const onSend = () => {
    const text = draft;
    setDraft("");
    sendMessage(text);
  };

  const renderItem = ({ item }: { item: DisplayItem }) => {
    switch (item.kind) {
      case "user":
        return <MessageBubble role="user" text={item.text} />;
      case "assistant":
        return <MessageBubble role="assistant" text={item.text} streaming={item.streaming} />;
      case "error":
        return <MessageBubble role="error" text={item.text} />;
      case "tool":
        return <ToolChip summary={item.summary} status={item.status} />;
    }
  };

  // Show typing dots while Chaka works but no reply text has streamed in yet
  const last = items[items.length - 1];
  const showTyping = busy && !(last?.kind === "assistant" && last.streaming && last.text.length > 0);

  const orbMode = !apiKey ? "off" : busy || listening ? "busy" : "idle";

  return (
    <View style={[styles.container, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        <View style={styles.titleRow}>
          <Orb size={22} mode={orbMode} />
          <Text style={styles.title}>CHAKA·MYTH</Text>
          {wakewordOn && (
            <View style={styles.wakeBadge}>
              <Feather name="mic" size={10} color={colors.primary} />
              <Text style={styles.wakeText}>hey chaka</Text>
            </View>
          )}
        </View>
        <View style={styles.headerActions}>
          <Pressable
            onPress={() => setActivityOpen(true)}
            hitSlop={12}
            accessibilityLabel="Activity log"
            style={({ pressed }) => pressed && styles.pressed}
          >
            <Feather name="list" size={21} color={colors.textSecondary} />
          </Pressable>
          <Pressable
            onPress={() => setSettingsOpen(true)}
            hitSlop={12}
            accessibilityLabel="Open settings"
            style={({ pressed }) => pressed && styles.pressed}
          >
            <Feather name="settings" size={21} color={colors.textSecondary} />
          </Pressable>
        </View>
      </View>

      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === "ios" ? "padding" : undefined}
        keyboardVerticalOffset={0}
      >
        <FlatList
          ref={listRef}
          style={styles.flex}
          data={items}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          contentContainerStyle={styles.listContent}
          ListFooterComponent={showTyping ? <TypingDots /> : null}
          ListEmptyComponent={
            <Animated.View entering={FadeIn.duration(500)} style={styles.empty}>
              <Orb size={110} mode={orbMode} />
              <Text style={styles.emptyTitle}>
                {apiKey ? "Chaka is ready." : "Almost there."}
              </Text>
              <Text style={styles.emptyHint}>
                {apiKey
                  ? "Ask me to do things — I can act, not just talk."
                  : "Open settings and paste your DeepSeek API key to wake me up."}
              </Text>
              {apiKey ? (
                <View style={styles.suggestions}>
                  {SUGGESTIONS.map((s, i) => (
                    <Animated.View key={s} entering={FadeInDown.delay(150 + i * 70).springify()}>
                      <Pressable
                        style={({ pressed }) => [styles.suggestion, pressed && styles.pressed]}
                        onPress={() => sendMessage(s)}
                      >
                        <Text style={styles.suggestionText}>{s}</Text>
                      </Pressable>
                    </Animated.View>
                  ))}
                </View>
              ) : (
                <Pressable style={styles.setupButton} onPress={() => setSettingsOpen(true)}>
                  <Text style={styles.setupButtonText}>Open settings</Text>
                </Pressable>
              )}
            </Animated.View>
          }
        />

        {pendingConfirmation && (
          <ConfirmCard
            summary={pendingConfirmation.summary}
            onApprove={() => resolveConfirmation(true)}
            onDecline={() => resolveConfirmation(false)}
            onAlways={
              pendingConfirmation.name === "operate_screen"
                ? () => {
                    useSettings.getState().setPrefs({ autoApproveScreen: true });
                    resolveConfirmation(true);
                  }
                : undefined
            }
          />
        )}

        <Composer
          value={draft}
          onChangeText={setDraft}
          onSend={onSend}
          onMicPress={onMicPress}
          onStop={stop}
          busy={busy}
          listening={listening}
          sttAvailable={sttAvailable()}
        />
        <View style={{ height: insets.bottom }} />
      </KeyboardAvoidingView>

      <SettingsSheet visible={settingsOpen} onClose={() => setSettingsOpen(false)} />
      <ActivityLog visible={activityOpen} onClose={() => setActivityOpen(false)} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.bg },
  flex: { flex: 1 },
  header: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingLeft: 8,
    paddingRight: 16,
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  titleRow: { flexDirection: "row", alignItems: "center", gap: 2 },
  headerActions: { flexDirection: "row", alignItems: "center", gap: 18 },
  title: {
    color: colors.text,
    fontSize: 15,
    fontFamily: fonts.display,
    letterSpacing: 3.5,
  },
  wakeBadge: {
    flexDirection: "row",
    alignItems: "center",
    gap: 4,
    marginLeft: 10,
    backgroundColor: colors.primarySoft,
    borderRadius: 999,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  wakeText: { color: colors.primary, fontSize: 10.5, fontFamily: fonts.semibold },
  listContent: { paddingVertical: 12, flexGrow: 1 },
  empty: { flex: 1, alignItems: "center", justifyContent: "center", padding: 28, gap: 6 },
  emptyTitle: {
    color: colors.text,
    fontSize: 22,
    fontFamily: fonts.display,
    marginTop: 4,
  },
  emptyHint: {
    color: colors.textDim,
    fontSize: 14.5,
    textAlign: "center",
    lineHeight: 21,
    fontFamily: fonts.regular,
    maxWidth: 280,
  },
  suggestions: { marginTop: 18, gap: 9, alignItems: "center" },
  suggestion: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 999,
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  suggestionText: { color: colors.textSecondary, fontSize: 13.5, fontFamily: fonts.medium },
  setupButton: {
    marginTop: 18,
    backgroundColor: colors.primary,
    borderRadius: 999,
    paddingHorizontal: 22,
    paddingVertical: 12,
  },
  setupButtonText: { color: colors.onPrimary, fontFamily: fonts.bold, fontSize: 15 },
  pressed: { opacity: 0.7 },
});
