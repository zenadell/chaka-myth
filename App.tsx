import React, { useEffect } from "react";
import { StyleSheet, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { CameraView } from "expo-camera";
import * as Notifications from "expo-notifications";
import { useFonts } from "expo-font";
import {
  Inter_400Regular,
  Inter_500Medium,
  Inter_600SemiBold,
  Inter_700Bold,
} from "@expo-google-fonts/inter";
import { SpaceGrotesk_500Medium, SpaceGrotesk_700Bold } from "@expo-google-fonts/space-grotesk";
import { ChatScreen } from "./src/ui/ChatScreen";
import { CameraCapture } from "./src/ui/components/CameraCapture";
import { VisionCapture } from "./src/ui/components/VisionCapture";
import { useSettings } from "./src/state/settings";
import { useDeviceFx } from "./src/state/deviceFx";
import { useMemory } from "./src/state/memory";
import { useAudit } from "./src/state/auditLog";
import { refreshMcpTools } from "./src/agent/mcp";
import * as Hands from "./modules/chaka-hands";

// Reminders should show even while the app is open.
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

export default function App() {
  const load = useSettings((s) => s.load);
  const loaded = useSettings((s) => s.loaded);
  const torchOn = useDeviceFx((s) => s.torchOn);

  const [fontsLoaded] = useFonts({
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
    Inter_700Bold,
    SpaceGrotesk_500Medium,
    SpaceGrotesk_700Bold,
  });

  useEffect(() => {
    load();
    useAudit.getState().load();
    // Load Chaka's persistent memory of the user.
    useMemory.getState().load();
    // Connect to any saved MCP servers in the background.
    refreshMcpTools().catch(() => {});
    // Hand the background notification service the current proactive setting + key,
    // and nudge Android to (re)bind the listener so it works right after granting.
    const s = useSettings.getState();
    if (s.apiKey) Hands.setProactive(s.prefs.proactive, s.apiKey);
    if (s.prefs.proactive) Hands.kickNotifications();
  }, [load]);

  if (!loaded || !fontsLoaded) return null;

  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      <ChatScreen />
      <CameraCapture />
      <VisionCapture />
      {torchOn && (
        <View style={styles.torchHost} pointerEvents="none">
          <CameraView style={StyleSheet.absoluteFill} enableTorch />
        </View>
      )}
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  // 1px invisible camera keeps the torch alive without showing a preview.
  torchHost: { position: "absolute", width: 1, height: 1, opacity: 0 },
});
