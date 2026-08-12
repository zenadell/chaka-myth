import React from "react";
import { FlatList, Modal, Pressable, StyleSheet, Text, View } from "react-native";
import { Feather } from "@expo/vector-icons";
import { useAudit, AuditEntry } from "../state/auditLog";
import { colors, fonts, radius } from "./theme";

const STATUS: Record<string, { icon: keyof typeof Feather.glyphMap; color: string }> = {
  ok: { icon: "check", color: colors.success },
  error: { icon: "x", color: colors.danger },
  declined: { icon: "slash", color: colors.textDim },
};

function timeAgo(iso: string): string {
  const s = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export function ActivityLog(props: { visible: boolean; onClose: () => void }) {
  const { entries, clear } = useAudit();

  const renderItem = ({ item }: { item: AuditEntry }) => {
    const st = STATUS[item.status];
    return (
      <View style={styles.row}>
        <Feather name={st.icon} size={15} color={st.color} style={{ marginTop: 2 }} />
        <View style={{ flex: 1 }}>
          <Text style={styles.summary}>{item.summary}</Text>
          {item.detail ? (
            <Text style={styles.detail} numberOfLines={2}>
              {item.detail}
            </Text>
          ) : null}
          <Text style={styles.meta}>
            {item.tool} · {timeAgo(item.ts)}
          </Text>
        </View>
      </View>
    );
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
          <Text style={styles.title}>Activity log</Text>
          <Pressable onPress={props.onClose} hitSlop={12}>
            <Text style={styles.close}>Done</Text>
          </Pressable>
        </View>
        <Text style={styles.hint}>
          Everything Chaka has done — every tool, what it accessed, and whether it worked. Stays on
          your device.
        </Text>
        <FlatList
          data={entries}
          keyExtractor={(e) => e.id}
          renderItem={renderItem}
          contentContainerStyle={styles.list}
          ListEmptyComponent={<Text style={styles.empty}>No activity yet.</Text>}
        />
        {entries.length > 0 && (
          <Pressable style={styles.clearBtn} onPress={clear}>
            <Text style={styles.clearText}>Clear log</Text>
          </Pressable>
        )}
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
  hint: { color: colors.textDim, fontSize: 13, fontFamily: fonts.regular, padding: 16, paddingBottom: 4 },
  list: { padding: 12, gap: 4 },
  row: {
    flexDirection: "row",
    gap: 12,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: 8,
  },
  summary: { color: colors.text, fontSize: 14.5, fontFamily: fonts.medium },
  detail: { color: colors.textSecondary, fontSize: 12.5, fontFamily: fonts.regular, marginTop: 3 },
  meta: { color: colors.textDim, fontSize: 11.5, fontFamily: fonts.regular, marginTop: 5 },
  empty: { color: colors.textDim, textAlign: "center", marginTop: 40, fontFamily: fonts.regular },
  clearBtn: {
    margin: 16,
    padding: 14,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.danger,
    alignItems: "center",
    backgroundColor: colors.dangerSoft,
  },
  clearText: { color: colors.danger, fontFamily: fonts.semibold, fontSize: 15 },
});
