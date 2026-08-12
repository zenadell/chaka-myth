import * as Location from "expo-location";
import type { Tool } from "../types";

export const getLocation: Tool = {
  definition: {
    type: "function",
    function: {
      name: "get_location",
      description:
        "Get the phone's current GPS location: coordinates plus a human-readable address " +
        "(street, city, region, country). Use when the user asks where they are or when " +
        "another task needs their precise location.",
      parameters: { type: "object", properties: {} },
    },
  },
  describeCall: () => "Get current location",
  execute: async () => {
    const { status } = await Location.requestForegroundPermissionsAsync();
    if (status !== "granted") return { ok: false, output: "Location permission denied." };
    const pos = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
    const { latitude, longitude } = pos.coords;
    let address: Record<string, string | null> | null = null;
    try {
      const results = await Location.reverseGeocodeAsync({ latitude, longitude });
      const a = results[0];
      if (a) {
        address = {
          street: a.street ?? a.name,
          district: a.district,
          city: a.city,
          region: a.region,
          country: a.country,
          postalCode: a.postalCode,
        };
      }
    } catch {
      /* geocoder unavailable — coordinates still useful */
    }
    return {
      ok: true,
      output: { latitude, longitude, accuracyMeters: pos.coords.accuracy, address },
    };
  },
};
