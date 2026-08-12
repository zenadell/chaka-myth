import * as MediaLibrary from "expo-media-library/legacy";
import * as Sharing from "expo-sharing";
import type { Tool } from "../types";

export const searchPhotos: Tool = {
  definition: {
    type: "function",
    function: {
      name: "search_media",
      description:
        "Search the phone's photos, videos, and audio by filename and/or date range. " +
        "Returns matches with name, date, and uri. Use for requests like " +
        "'find that photo I took in March' or 'find the song file called X'.",
      parameters: {
        type: "object",
        properties: {
          query: {
            type: "string",
            description: "Filename fragment to match (case-insensitive). Omit to match everything in range.",
          },
          mediaType: {
            type: "string",
            enum: ["photo", "video", "audio", "all"],
            description: "Kind of media. Default photo.",
          },
          takenAfter: { type: "string", description: "ISO date lower bound, e.g. 2026-03-01" },
          takenBefore: { type: "string", description: "ISO date upper bound" },
          limit: { type: "number", description: "Max results (default 15, max 40)" },
        },
      },
    },
  },
  describeCall: (args) =>
    `Search ${args.mediaType ?? "photo"}s${args.query ? ` for "${args.query}"` : ""}`,
  execute: async (args) => {
    const { status } = await MediaLibrary.requestPermissionsAsync();
    if (status !== "granted") {
      return { ok: false, output: "Media library permission denied by the user." };
    }
    const typeMap: Record<string, MediaLibrary.MediaTypeValue[]> = {
      photo: [MediaLibrary.MediaType.photo],
      video: [MediaLibrary.MediaType.video],
      audio: [MediaLibrary.MediaType.audio],
      all: [MediaLibrary.MediaType.photo, MediaLibrary.MediaType.video, MediaLibrary.MediaType.audio],
    };
    const mediaType = typeMap[args.mediaType as string] ?? typeMap.photo;
    const query = args.query ? String(args.query).toLowerCase() : null;
    const limit = Math.min(Number(args.limit) || 15, 40);

    const matches: { name: string; date: string; uri: string }[] = [];
    let after: string | undefined;
    let scanned = 0;

    while (matches.length < limit && scanned < 3000) {
      const page = await MediaLibrary.getAssetsAsync({
        first: 200,
        after,
        mediaType,
        sortBy: [MediaLibrary.SortBy.creationTime],
        createdAfter: args.takenAfter ? new Date(args.takenAfter) : undefined,
        createdBefore: args.takenBefore ? new Date(args.takenBefore) : undefined,
      });
      scanned += page.assets.length;
      for (const asset of page.assets) {
        if (!query || asset.filename.toLowerCase().includes(query)) {
          matches.push({
            name: asset.filename,
            date: new Date(asset.creationTime).toISOString().slice(0, 10),
            uri: asset.uri,
          });
          if (matches.length >= limit) break;
        }
      }
      if (!page.hasNextPage) break;
      after = page.endCursor;
    }

    if (!matches.length) {
      return { ok: false, output: `No media found${query ? ` matching "${args.query}"` : ""} (scanned ${scanned} items).` };
    }
    return { ok: true, output: { count: matches.length, scanned, matches } };
  },
};

export const shareFile: Tool = {
  definition: {
    type: "function",
    function: {
      name: "open_or_share_file",
      description:
        "Open the system share/open dialog for a file uri (from search_media or search_files results). " +
        "The user picks which app opens or receives it — gallery, WhatsApp, email, etc.",
      parameters: {
        type: "object",
        properties: {
          uri: { type: "string", description: "The file/asset uri to open or share" },
        },
        required: ["uri"],
      },
    },
  },
  describeCall: () => "Open share dialog for file",
  execute: async (args) => {
    if (!(await Sharing.isAvailableAsync())) {
      return { ok: false, output: "Sharing is not available on this device." };
    }
    await Sharing.shareAsync(String(args.uri));
    return { ok: true, output: "Share dialog opened." };
  },
};
