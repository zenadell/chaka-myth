import AsyncStorage from "@react-native-async-storage/async-storage";
import { Directory, File } from "expo-file-system";
import type { Tool } from "../types";

const GRANTED_DIRS_KEY = "chaka.grantedDirs";

async function loadGrantedDirs(): Promise<string[]> {
  const raw = await AsyncStorage.getItem(GRANTED_DIRS_KEY);
  return raw ? JSON.parse(raw) : [];
}

export const grantFolderAccess: Tool = {
  definition: {
    type: "function",
    function: {
      name: "grant_folder_access",
      description:
        "Show the system folder picker so the user can grant Chaka access to a folder " +
        "(e.g. Downloads, Documents). Required once before search_files can search that folder. " +
        "Tell the user which folder to pick before calling this.",
      parameters: { type: "object", properties: {} },
    },
  },
  describeCall: () => "Ask for folder access",
  execute: async () => {
    try {
      const dir = await Directory.pickDirectoryAsync();
      const dirs = await loadGrantedDirs();
      if (!dirs.includes(dir.uri)) dirs.push(dir.uri);
      await AsyncStorage.setItem(GRANTED_DIRS_KEY, JSON.stringify(dirs));
      return { ok: true, output: `Access granted to folder: ${dir.name ?? dir.uri}` };
    } catch {
      return { ok: false, output: "User cancelled the folder picker." };
    }
  },
};

export const searchFiles: Tool = {
  definition: {
    type: "function",
    function: {
      name: "search_files",
      description:
        "Search granted folders (see grant_folder_access) recursively for files whose name " +
        "contains the query. Returns name, folder, and uri (pass uri to open_or_share_file). " +
        "If no folder access has been granted yet, this reports that — then use grant_folder_access.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "Filename fragment, case-insensitive" },
        },
        required: ["query"],
      },
    },
  },
  describeCall: (args) => `Search files for "${args.query}"`,
  execute: async (args) => {
    const dirs = await loadGrantedDirs();
    if (!dirs.length) {
      return {
        ok: false,
        output:
          "No folders granted yet. Use grant_folder_access first (ask the user to pick e.g. Downloads).",
      };
    }
    const q = String(args.query).toLowerCase();
    const results: { name: string; folder: string; uri: string }[] = [];
    let visited = 0;
    const staleDirs: string[] = [];

    const walk = (dir: Directory, folderName: string, depth: number) => {
      if (depth > 8 || visited > 4000 || results.length >= 25) return;
      let entries: (Directory | File)[];
      try {
        entries = dir.list();
      } catch {
        return;
      }
      for (const entry of entries) {
        visited++;
        if (results.length >= 25 || visited > 4000) return;
        if (entry instanceof Directory) {
          walk(entry, entry.name ?? folderName, depth + 1);
        } else if ((entry.name ?? "").toLowerCase().includes(q)) {
          results.push({ name: entry.name ?? "?", folder: folderName, uri: entry.uri });
        }
      }
    };

    for (const uri of dirs) {
      try {
        const dir = new Directory(uri);
        walk(dir, dir.name ?? "granted folder", 0);
      } catch {
        staleDirs.push(uri);
      }
    }

    if (staleDirs.length) {
      const fresh = dirs.filter((d) => !staleDirs.includes(d));
      await AsyncStorage.setItem(GRANTED_DIRS_KEY, JSON.stringify(fresh));
    }

    if (!results.length) {
      return {
        ok: false,
        output: `No files matching "${args.query}" in granted folders (checked ${visited} entries).` +
          (staleDirs.length ? " Some folder grants expired — you may need grant_folder_access again." : ""),
      };
    }
    return { ok: true, output: { count: results.length, matches: results } };
  },
};
