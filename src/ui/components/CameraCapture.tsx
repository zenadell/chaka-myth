import React, { useEffect, useRef, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { CameraView } from "expo-camera";
import * as MediaLibrary from "expo-media-library/legacy";
import { Feather } from "@expo/vector-icons";
import { useDeviceFx } from "../../state/deviceFx";
import { completeCapture } from "../../agent/tools/cameraTools";
import { colors, fonts } from "../theme";

/** Full-screen capture overlay: 3-2-1 countdown, snap, save to gallery. */
export function CameraCapture() {
  const request = useDeviceFx((s) => s.cameraRequest);
  const camRef = useRef<CameraView>(null);
  const [count, setCount] = useState(3);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    setCount(3);
    setReady(false);
  }, [request]);

  useEffect(() => {
    if (!request || !ready) return;
    if (count > 0) {
      const t = setTimeout(() => setCount((c) => c - 1), 1000);
      return () => clearTimeout(t);
    }
    (async () => {
      try {
        const photo = await camRef.current?.takePictureAsync({ quality: 0.9 });
        if (!photo?.uri) throw new Error("Capture returned no image");
        const asset = await MediaLibrary.createAssetAsync(photo.uri);
        completeCapture({ uri: asset.uri });
      } catch (err: any) {
        completeCapture({ error: err?.message ?? "Capture failed" });
      }
    })();
  }, [request, ready, count]);

  if (!request) return null;

  return (
    <View style={styles.overlay}>
      <CameraView
        ref={camRef}
        style={StyleSheet.absoluteFill}
        facing={request.facing}
        onCameraReady={() => setReady(true)}
      />
      <View style={styles.hud} pointerEvents="box-none">
        {count > 0 && <Text style={styles.count}>{count}</Text>}
        <Pressable
          style={styles.cancel}
          onPress={() => completeCapture({ error: "User cancelled the capture." })}
          accessibilityLabel="Cancel photo"
        >
          <Feather name="x" size={22} color={colors.text} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: "#000",
    zIndex: 100,
  },
  hud: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: "center",
    justifyContent: "center",
  },
  count: {
    color: colors.primary,
    fontSize: 120,
    fontFamily: fonts.display,
    textShadowColor: "rgba(0,0,0,0.6)",
    textShadowRadius: 16,
  },
  cancel: {
    position: "absolute",
    top: 60,
    right: 24,
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: "rgba(0,0,0,0.5)",
    alignItems: "center",
    justifyContent: "center",
  },
});
