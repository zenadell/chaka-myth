import { Directory, File, Paths } from "expo-file-system";
import * as Hands from "../../modules/chaka-hands";

/**
 * Self-update: Chaka checks her own GitHub Releases for a newer APK, downloads
 * it, and hands it to the Android installer. Native changes (Kotlin) can't ship
 * over expo-updates, so the whole APK is replaced instead.
 *
 * Set CHAKA_REPO to "<owner>/<repo>" once the GitHub repo exists.
 */
export const CHAKA_REPO = "";

const RELEASES_API = (repo: string) =>
  `https://api.github.com/repos/${repo}/releases/latest`;

export type UpdateInfo = {
  version: string;
  versionCode: number;
  url: string;
  notes: string;
  sizeMB: number;
};

/** "2.2.1" -> 20201, matching the versionCode formula in android/app/build.gradle. */
function versionCodeOf(version: string): number {
  const [maj = 0, min = 0, patch = 0] = version
    .replace(/^v/i, "")
    .split(".")
    .map((n) => parseInt(n, 10) || 0);
  return maj * 10000 + min * 100 + patch;
}

/**
 * Returns the newer release, or null when already current / unreachable.
 * Never throws — a failed check must not disturb the user.
 */
export async function checkForUpdate(repo = CHAKA_REPO): Promise<UpdateInfo | null> {
  if (!repo || !Hands.canSelfUpdate()) return null;
  try {
    const res = await fetch(RELEASES_API(repo), {
      headers: { Accept: "application/vnd.github+json" },
    });
    if (!res.ok) return null;
    const data = await res.json();

    const tag: string = data.tag_name ?? "";
    const remoteCode = versionCodeOf(tag);
    const current = Hands.appVersion();
    if (!current || remoteCode <= current.versionCode) return null;

    const asset = (data.assets ?? []).find((a: any) =>
      String(a.name).endsWith(".apk")
    );
    if (!asset) return null;

    return {
      version: tag.replace(/^v/i, ""),
      versionCode: remoteCode,
      url: asset.browser_download_url,
      notes: String(data.body ?? "").slice(0, 400),
      sizeMB: Math.round(((asset.size ?? 0) / 1048576) * 10) / 10,
    };
  } catch {
    return null;
  }
}

/**
 * Downloads the update and opens the system installer. The user still confirms
 * the install on Android's own screen — we never install silently.
 */
export async function downloadAndInstall(
  update: UpdateInfo,
  onProgress?: (pct: number) => void
): Promise<void> {
  if (!Hands.canInstallPackages()) {
    Hands.requestInstallPermission();
    throw new Error(
      "Android needs permission to install updates — I opened the setting, turn it on and try again."
    );
  }

  const dir = new Directory(Paths.cache, "updates");
  if (!dir.exists) dir.create();

  // Clear older downloads so the cache doesn't accumulate 50MB APKs.
  for (const old of dir.list()) {
    try {
      old.delete();
    } catch {
      /* best effort */
    }
  }

  onProgress?.(0);
  const file = await File.downloadFileAsync(update.url, dir);
  onProgress?.(100);

  Hands.installApk(file.uri);
}
