import { create } from "zustand";

/**
 * Device-effect state that must be rendered to take effect.
 * The torch requires a mounted (hidden) CameraView with enableTorch,
 * which App.tsx renders while torchOn is true.
 */
export interface CameraRequest {
  facing: "front" | "back";
}

export interface VisionRequest {
  facing: "front" | "back";
  question: string;
}

interface DeviceFxState {
  torchOn: boolean;
  setTorch: (on: boolean) => void;
  /** When set, App renders the CameraCapture overlay (countdown + snap). */
  cameraRequest: CameraRequest | null;
  setCameraRequest: (req: CameraRequest | null) => void;
  /** When set, App renders the VisionCapture overlay (live "look at this"). */
  visionRequest: VisionRequest | null;
  setVisionRequest: (req: VisionRequest | null) => void;
}

export const useDeviceFx = create<DeviceFxState>((set) => ({
  torchOn: false,
  setTorch: (on) => set({ torchOn: on }),
  cameraRequest: null,
  setCameraRequest: (req) => set({ cameraRequest: req }),
  visionRequest: null,
  setVisionRequest: (req) => set({ visionRequest: req }),
}));
