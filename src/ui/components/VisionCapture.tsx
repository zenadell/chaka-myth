import React, { useEffect, useRef, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { CameraView } from "expo-camera";
import { Feather } from "@expo/vector-icons";
import { useDeviceFx } from "../../state/deviceFx";
import { completeVision } from "../../agent/tools/visionTools";
import { colors, fonts } from "../theme";

/**
 * Full-screen live camera overlay for "look at this". Shows the live feed, a
 * one-line prompt of what Chaka is looking for, snaps a frame on tap (or auto
 * after a moment), and hands the base64 frame back to the tool for Gemini.
 */
export function VisionCapture() {
  const request = useDeviceFx((s) => s.visionRequest);
  const camRef = useRef<CameraView>(null);
  const [ready, setReady] = useState(false);
  const [capturing, setCapturing] = useState(false);

  useEffect(() => {
    setReady(false);
    setCapturing(false);
  }, [request]);

  const snap = async () => {
    if (!ready || capturing) return;
    setCapturing(true);
    try {
      const photo = await camRef.current?.takePictureAsync({ quality: 0.6, base64: true });
      if (!photo?.base64) throw new Error("Couldn't capture the frame");
      completeVision({ base64: photo.base64 });
    } catch (err: any) {
      completeVision({ error: err?.message ?? "Capture failed" });
    }
  };

  if (!request) return null;

  return (
    <View style={styles.overlay}>
      <CameraView
        ref={camRef}
        style={StyleSheet.absoluteFill}
        facing={request.facing}
        onCameraReady={() => setReady(true)}
      />
      <View style={styles.topBar} pointerEvents="box-none">
        <View style={styles.prompt}>
          <Feather name="eye" size={15} color={colors.primary} />
          <Text style={styles.promptText} numberOfLines={2}>
            {request.question}
          </Text>
        </View>
        <Pressable
          style={styles.cancel}
          onPress={() => completeVision({ error: "User cancelled." })}
          accessibilityLabel="Cancel"
        >
          <Feather name="x" size={22} color={colors.text} />
        </Pressable>
      </View>

      <View style={styles.bottomBar} pointerEvents="box-none">
        <Pressable style={styles.shutter} onPress={snap} accessibilityLabel="Capture">
          <View style={[styles.shutterInner, capturing && styles.shutterBusy]}>
            {capturing ? (
              <Feather name="loader" size={22} color={colors.onPrimary} />
            ) : (
              <Feather name="eye" size={24} color={colors.onPrimary} />
            )}
          </View>
        </Pressable>
        <Text style={styles.hint}>{capturing ? "Looking…" : "Tap to let Chaka look"}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: { position: "absolute", top: 0, left: 0, right: 0, bottom: 0, backgroundColor: "#000", zIndex: 100 },
  topBar: {
    position: "absolute",
    top: 50,
    left: 16,
    right: 16,
    flexDirection: "row",
    alignItems: "flex-start",
    gap: 10,
  },
  prompt: {
    flex: 1,
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    backgroundColor: "rgba(0,0,0,0.55)",
    borderRadius: 14,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  promptText: { color: colors.text, fontSize: 14, fontFamily: fonts.medium, flex: 1 },
  cancel: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: "rgba(0,0,0,0.5)",
    alignItems: "center",
    justifyContent: "center",
  },
  bottomBar: { position: "absolute", bottom: 60, left: 0, right: 0, alignItems: "center", gap: 12 },
  shutter: {
    width: 78,
    height: 78,
    borderRadius: 39,
    borderWidth: 4,
    borderColor: "rgba(255,255,255,0.5)",
    alignItems: "center",
    justifyContent: "center",
  },
  shutterInner: {
    width: 62,
    height: 62,
    borderRadius: 31,
    backgroundColor: colors.primary,
    alignItems: "center",
    justifyContent: "center",
  },
  shutterBusy: { backgroundColor: colors.primaryDeep },
  hint: {
    color: colors.text,
    fontSize: 13,
    fontFamily: fonts.medium,
    backgroundColor: "rgba(0,0,0,0.5)",
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderRadius: 10,
  },
});
