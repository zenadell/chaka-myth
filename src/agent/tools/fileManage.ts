import { Directory, File } from "expo-file-system";
import type { Tool } from "../types";

export const manageFile: Tool = {
  definition: {
    type: "function",
    function: {
      name: "manage_file",
      description:
        "Delete, rename, move, or copy a file. Get the file uri from search_files or search_media first. " +
        "For move/copy, destinationFolderUri must be a granted folder uri (or a subfolder found via search). " +
        "For rename, provide newName.",
      parameters: {
        type: "object",
        properties: {
          action: { type: "string", enum: ["delete", "rename", "move", "copy"] },
          uri: { type: "string", description: "The file uri to operate on" },
          newName: { type: "string", description: "New filename (rename only)" },
          destinationFolderUri: { type: "string", description: "Destination folder uri (move/copy)" },
        },
        required: ["action", "uri"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => {
    const name = String(args.uri).split("/").pop()?.slice(0, 40) ?? args.uri;
    switch (args.action) {
      case "delete": return `Delete file "${name}"`;
      case "rename": return `Rename "${name}" to "${args.newName}"`;
      case "move": return `Move "${name}" to another folder`;
      default: return `Copy "${name}" to another folder`;
    }
  },
  execute: async (args) => {
    const file = new File(String(args.uri));
    switch (args.action) {
      case "delete":
        file.delete();
        return { ok: true, output: "File deleted." };
      case "rename": {
        if (!args.newName) return { ok: false, output: "newName is required for rename." };
        const dest = new File(file.parentDirectory, String(args.newName));
        await file.move(dest);
        return { ok: true, output: `Renamed to ${args.newName}` };
      }
      case "move": {
        if (!args.destinationFolderUri) return { ok: false, output: "destinationFolderUri required." };
        await file.move(new Directory(String(args.destinationFolderUri)));
        return { ok: true, output: "File moved." };
      }
      case "copy": {
        if (!args.destinationFolderUri) return { ok: false, output: "destinationFolderUri required." };
        await file.copy(new Directory(String(args.destinationFolderUri)));
        return { ok: true, output: "File copied." };
      }
      default:
        return { ok: false, output: `Unknown action ${args.action}` };
    }
  },
};
