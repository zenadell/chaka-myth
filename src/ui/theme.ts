/**
 * Chaka-Myth "Ember" design language.
 * Deep warm black, ember orange, clean white. 4pt spacing rhythm.
 */
export const colors = {
  bg: "#0A0806",
  surface: "#171210",
  surfaceRaised: "#201915",
  border: "#2C231C",
  borderBright: "#413327",

  primary: "#FF6B1A",
  primaryBright: "#FF8C42",
  primaryDeep: "#E14E00",
  primaryGlow: "rgba(255, 107, 26, 0.35)",
  primarySoft: "rgba(255, 107, 26, 0.14)",
  onPrimary: "#140A03",

  danger: "#FF5252",
  dangerSoft: "rgba(255, 82, 82, 0.12)",
  success: "#4ADE80",

  text: "#FFFFFF",
  textSecondary: "#CFC4BA",
  textDim: "#8F8175",

  userBubbleFrom: "#FF7B2E",
  userBubbleTo: "#E14E00",
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
};

export const radius = {
  sm: 10,
  md: 14,
  lg: 20,
  xl: 26,
  full: 999,
};

export const fonts = {
  regular: "Inter_400Regular",
  medium: "Inter_500Medium",
  semibold: "Inter_600SemiBold",
  bold: "Inter_700Bold",
  display: "SpaceGrotesk_700Bold",
  displayMedium: "SpaceGrotesk_500Medium",
};

export const type = {
  body: { fontFamily: fonts.regular, fontSize: 15.5, lineHeight: 23, color: colors.text },
  label: { fontFamily: fonts.medium, fontSize: 13, color: colors.textSecondary },
  caption: { fontFamily: fonts.medium, fontSize: 11.5, color: colors.textDim },
  section: {
    fontFamily: fonts.semibold,
    fontSize: 11.5,
    letterSpacing: 1.4,
    textTransform: "uppercase" as const,
    color: colors.textDim,
  },
};
