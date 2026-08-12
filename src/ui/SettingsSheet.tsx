import React, { useEffect, useState } from "react";
import {
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from "react-native";
import { Feather } from "@expo/vector-icons";
import { useSettings } from "../state/settings";
import { useChat } from "../state/chatStore";
import {
  loadMcpServers,
  saveMcpServers,
  refreshMcpTools,
  getMcpErrors,
  McpServerConfig,
} from "../agent/mcp";
import * as Hands from "../../modules/chaka-hands";
import { VISION_MODEL } from "../state/settings";
import { colors, fonts, radius } from "./theme";

const MODELS = [
  { id: "deepseek-v4-flash", label: "V4 Flash", hint: "fast + cheap, great for daily use" },
  { id: "deepseek-v4-pro", label: "V4 Pro", hint: "strongest, for hard tasks" },
];

export function SettingsSheet(props: { visible: boolean; onClose: () => void }) {
  const { apiKey, setApiKey, geminiKey, setGeminiKey, prefs, setPrefs } = useSettings();
  const clearChat = useChat((s) => s.clearChat);
  const [keyDraft, setKeyDraft] = useState("");
  const [geminiDraft, setGeminiDraft] = useState("");
  const [mcpServers, setMcpServers] = useState<McpServerConfig[]>([]);
  const [mcpUrlDraft, setMcpUrlDraft] = useState("");
  const [mcpStatus, setMcpStatus] = useState<Record<string, number> | null>(null);
  const [mcpBusy, setMcpBusy] = useState(false);
  const [handsEnabled, setHandsEnabled] = useState(false);
  const [batteryExempt, setBatteryExempt] = useState(true);
  const [notifAccess, setNotifAccess] = useState(true);
  const handsAvailable = Hands.available();

  useEffect(() => {
    if (props.visible) {
      loadMcpServers().then(setMcpServers);
      setHandsEnabled(Hands.isEnabled());
      setBatteryExempt(Hands.isBatteryExempt());
      setNotifAccess(Hands.isNotificationAccessGranted());
    }
  }, [props.visible]);

  const reconnectMcp = async () => {
    setMcpBusy(true);
    try {
      setMcpStatus(await refreshMcpTools());
    } finally {
      setMcpBusy(false);
    }
  };

  const addMcpServer = async () => {
    const url = mcpUrlDraft.trim();
    if (!url) return;
    let name: string;
    try {
      name = new URL(url).hostname.split(".")[0] || "server";
    } catch {
      name = "server";
    }
    const next = [...mcpServers, { name, url }];
    setMcpServers(next);
    setMcpUrlDraft("");
    await saveMcpServers(next);
    await reconnectMcp();
  };

  const removeMcpServer = async (url: string) => {
    const next = mcpServers.filter((s) => s.url !== url);
    setMcpServers(next);
    await saveMcpServers(next);
    await reconnectMcp();
  };

  return (
    <Modal
      visible={props.visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={props.onClose}
    >
      <View style={styles.container}>
        <View style={styles.header}>
          <Text style={styles.title}>Settings</Text>
          <Pressable onPress={props.onClose} hitSlop={12}>
            <Text style={styles.close}>Done</Text>
          </Pressable>
        </View>

        <ScrollView contentContainerStyle={styles.body}>
          <Text style={styles.sectionLabel}>DeepSeek API key</Text>
          <Text style={styles.hint}>
            {apiKey
              ? `Key saved (…${apiKey.slice(-4)}). Stored in the device secure enclave.`
              : "Paste your key from platform.deepseek.com"}
          </Text>
          <View style={styles.keyRow}>
            <TextInput
              style={styles.keyInput}
              value={keyDraft}
              onChangeText={setKeyDraft}
              placeholder="sk-…"
              placeholderTextColor={colors.textDim}
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry
            />
            <Pressable
              style={({ pressed }) => [
                styles.saveButton,
                !keyDraft.trim() && { opacity: 0.4 },
                pressed && styles.pressed,
              ]}
              disabled={!keyDraft.trim()}
              onPress={async () => {
                await setApiKey(keyDraft);
                setKeyDraft("");
              }}
            >
              <Text style={styles.saveText}>Save</Text>
            </Pressable>
          </View>

          <Text style={styles.sectionLabel}>Model</Text>
          {MODELS.map((m) => (
            <Pressable
              key={m.id}
              style={({ pressed }) => [
                styles.modelRow,
                prefs.model === m.id && styles.modelRowActive,
                pressed && styles.pressed,
              ]}
              onPress={() => setPrefs({ model: m.id })}
            >
              <View style={{ flex: 1 }}>
                <Text style={styles.modelLabel}>{m.label}</Text>
                <Text style={styles.hint}>{m.hint}</Text>
              </View>
              {prefs.model === m.id && <Feather name="check" size={18} color={colors.primary} />}
            </Pressable>
          ))}

          <Text style={styles.sectionLabel}>Voice</Text>
          <View style={styles.switchRow}>
            <Text style={styles.switchLabel}>Speak replies aloud</Text>
            <Switch
              value={prefs.speakReplies}
              onValueChange={(v) => setPrefs({ speakReplies: v })}
              trackColor={{ true: colors.primaryDeep, false: colors.border }}
              thumbColor={prefs.speakReplies ? colors.primaryBright : colors.textDim}
            />
          </View>

          <Text style={styles.sectionLabel}>Proactive (autonomy)</Text>
          <Text style={styles.hint}>
            Chaka watches your notifications and pings you only about things that truly matter — a
            real message, money, something time-sensitive. It judges privately on-device.
          </Text>
          <View style={styles.switchRow}>
            <Text style={[styles.switchLabel, { flex: 1 }]}>Proactive heads-ups</Text>
            <Switch
              value={prefs.proactive}
              onValueChange={(v) => {
                setPrefs({ proactive: v });
                if (v && !Hands.isNotificationAccessGranted()) {
                  Hands.requestNotificationAccess();
                }
                Hands.setProactive(v, apiKey ?? "");
              }}
              trackColor={{ true: colors.primaryDeep, false: colors.border }}
              thumbColor={prefs.proactive ? colors.primaryBright : colors.textDim}
            />
          </View>
          {prefs.proactive && !notifAccess && (
            <Pressable
              style={({ pressed }) => [styles.handsRow, { borderColor: colors.danger, marginTop: 8 }, pressed && styles.pressed]}
              onPress={() => Hands.requestNotificationAccess()}
            >
              <Feather name="bell" size={16} color={colors.danger} />
              <Text style={[styles.switchLabel, { flex: 1 }]}>
                Grant notification access so Chaka can read alerts
              </Text>
              <Feather name="chevron-right" size={18} color={colors.textDim} />
            </Pressable>
          )}

          <Text style={styles.sectionLabel}>Screen control (Chaka Hands)</Text>
          <Text style={styles.hint}>
            Lets Chaka see the screen and tap/type for you — so it can do anything in any app, even
            turn Bluetooth or Wi-Fi on directly. You grant it once.
          </Text>
          {!handsAvailable ? (
            <View style={styles.handsRow}>
              <Feather name="alert-circle" size={16} color={colors.textDim} />
              <Text style={[styles.hint, { marginBottom: 0, flex: 1 }]}>
                Available after you install the 0.4.0 APK.
              </Text>
            </View>
          ) : (
            <Pressable
              style={({ pressed }) => [styles.handsRow, pressed && styles.pressed]}
              onPress={() => {
                Hands.openAccessibilitySettings();
              }}
            >
              <Feather
                name={handsEnabled ? "check-circle" : "shield"}
                size={16}
                color={handsEnabled ? colors.success : colors.primary}
              />
              <Text style={[styles.switchLabel, { flex: 1 }]}>
                {handsEnabled ? "Enabled — Chaka can control the screen" : "Tap to enable in Accessibility"}
              </Text>
              <Feather name="chevron-right" size={18} color={colors.textDim} />
            </Pressable>
          )}

          {handsAvailable && (
            <View style={[styles.switchRow, { marginTop: 8 }]}>
              <View style={{ flex: 1, paddingRight: 12 }}>
                <Text style={styles.switchLabel}>Always allow (don't ask each time)</Text>
                <Text style={[styles.hint, { marginBottom: 0, marginTop: 2 }]}>
                  Skip the approval card before Chaka controls the screen.
                </Text>
              </View>
              <Switch
                value={prefs.autoApproveScreen}
                onValueChange={(v) => setPrefs({ autoApproveScreen: v })}
                trackColor={{ true: colors.primaryDeep, false: colors.border }}
                thumbColor={prefs.autoApproveScreen ? colors.primaryBright : colors.textDim}
              />
            </View>
          )}

          <Text style={styles.hint}>
            For reliable control of any app (photo grids, Spotify, custom UIs), add a Gemini vision key —
            Chaka will then SEE the screen, not just read labels. Free key from aistudio.google.com/apikey.
            Uses {VISION_MODEL}.
          </Text>
          <View style={styles.keyRow}>
            <TextInput
              style={styles.keyInput}
              value={geminiDraft}
              onChangeText={setGeminiDraft}
              placeholder={geminiKey ? `Gemini key saved (…${geminiKey.slice(-4)})` : "Gemini API key (AIza…)"}
              placeholderTextColor={colors.textDim}
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry
            />
            <Pressable
              style={({ pressed }) => [
                styles.saveButton,
                !geminiDraft.trim() && { opacity: 0.4 },
                pressed && styles.pressed,
              ]}
              disabled={!geminiDraft.trim()}
              onPress={async () => {
                await setGeminiKey(geminiDraft);
                setGeminiDraft("");
              }}
            >
              <Text style={styles.saveText}>Save</Text>
            </Pressable>
          </View>

          {handsAvailable && !batteryExempt && (
            <Pressable
              style={({ pressed }) => [styles.handsRow, { borderColor: colors.danger }, pressed && styles.pressed]}
              onPress={() => {
                Hands.requestBatteryExemption();
              }}
            >
              <Feather name="battery-charging" size={16} color={colors.danger} />
              <Text style={[styles.switchLabel, { flex: 1 }]}>
                Stop Android killing Chaka mid-task — tap to allow unrestricted battery
              </Text>
              <Feather name="chevron-right" size={18} color={colors.textDim} />
            </Pressable>
          )}

          <Text style={styles.sectionLabel}>MCP connectors</Text>
          <Text style={styles.hint}>
            Plug Chaka into MCP servers — their tools become Chaka's tools. Paste a streamable-HTTP
            MCP server URL.
          </Text>
          {mcpServers.map((s) => (
            <View key={s.url} style={styles.mcpRow}>
              <View style={{ flex: 1 }}>
                <Text style={styles.modelLabel}>{s.name}</Text>
                <Text style={styles.hint} numberOfLines={1}>
                  {mcpStatus && mcpStatus[s.name] !== undefined
                    ? getMcpErrors()[s.name]
                      ? `error: ${getMcpErrors()[s.name]}`
                      : `${mcpStatus[s.name]} tools connected`
                    : s.url}
                </Text>
              </View>
              <Pressable onPress={() => removeMcpServer(s.url)} hitSlop={10}>
                <Feather name="trash-2" size={17} color={colors.danger} />
              </Pressable>
            </View>
          ))}
          <View style={styles.keyRow}>
            <TextInput
              style={styles.keyInput}
              value={mcpUrlDraft}
              onChangeText={setMcpUrlDraft}
              placeholder="https://my-server.example/mcp"
              placeholderTextColor={colors.textDim}
              autoCapitalize="none"
              autoCorrect={false}
            />
            <Pressable
              style={({ pressed }) => [
                styles.saveButton,
                (!mcpUrlDraft.trim() || mcpBusy) && { opacity: 0.4 },
                pressed && styles.pressed,
              ]}
              disabled={!mcpUrlDraft.trim() || mcpBusy}
              onPress={addMcpServer}
            >
              <Text style={styles.saveText}>{mcpBusy ? "…" : "Add"}</Text>
            </Pressable>
          </View>

          <Text style={styles.sectionLabel}>Conversation</Text>
          <Pressable
            style={({ pressed }) => [styles.dangerButton, pressed && styles.pressed]}
            onPress={() => {
              clearChat();
              props.onClose();
            }}
          >
            <Text style={styles.dangerText}>Clear chat history</Text>
          </Pressable>
        </ScrollView>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.bg },
  header: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  title: { color: colors.text, fontSize: 17, fontFamily: fonts.display, letterSpacing: 1 },
  close: { color: colors.primary, fontSize: 16, fontFamily: fonts.semibold },
  body: { padding: 16, gap: 4 },
  sectionLabel: {
    color: colors.textDim,
    fontSize: 11.5,
    textTransform: "uppercase",
    letterSpacing: 1.4,
    fontFamily: fonts.semibold,
    marginTop: 22,
    marginBottom: 8,
  },
  hint: { color: colors.textDim, fontSize: 13, marginBottom: 8, fontFamily: fonts.regular },
  keyRow: { flexDirection: "row", gap: 8 },
  keyInput: {
    flex: 1,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: 14,
    paddingVertical: 11,
    color: colors.text,
    fontSize: 15,
    fontFamily: fonts.regular,
  },
  saveButton: {
    backgroundColor: colors.primary,
    borderRadius: radius.md,
    paddingHorizontal: 18,
    justifyContent: "center",
  },
  saveText: { color: colors.onPrimary, fontFamily: fonts.bold },
  modelRow: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: 14,
    marginBottom: 8,
  },
  modelRowActive: { borderColor: colors.primary },
  handsRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: 14,
  },
  mcpRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: 14,
    marginBottom: 8,
  },
  modelLabel: {
    color: colors.text,
    fontSize: 15.5,
    fontFamily: fonts.semibold,
    marginBottom: 2,
  },
  switchRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: 14,
  },
  switchLabel: { color: colors.text, fontSize: 15.5, fontFamily: fonts.regular },
  dangerButton: {
    backgroundColor: colors.dangerSoft,
    borderWidth: 1,
    borderColor: colors.danger,
    borderRadius: radius.md,
    padding: 14,
    alignItems: "center",
  },
  dangerText: { color: colors.danger, fontFamily: fonts.semibold, fontSize: 15 },
  pressed: { opacity: 0.75 },
});
