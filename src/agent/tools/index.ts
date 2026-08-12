import type { Tool, ToolDefinition } from "../types";
import {
  getDeviceStatus,
  openSettings,
  setBrightness,
  flashlight,
  clipboardTool,
  openApp,
  openDownloads,
} from "./deviceTools";
import { findContact, whatsappMessage, openUrl } from "./commsTools";
import { searchPhotos, shareFile } from "./mediaTools";
import { grantFolderAccess, searchFiles } from "./fileTools";
import { webSearch, readWebpage, getWeather } from "./webTools";
import { scheduleReminder, listReminders, cancelReminder } from "./reminderTools";
import { saveNote, listNotes, deleteNote, remember, recallProfile, forgetFact } from "./memoryTools";
import { dailyBriefing } from "./briefingTools";
import { deepResearch } from "../research";
import { getLocation } from "./locationTools";
import { manageFile } from "./fileManage";
import { takePhoto } from "./cameraTools";
import { look } from "./visionTools";
import { guideMe } from "./guideTools";
import { callNumber, setAlarm, setTimer, uninstallApp } from "./phoneTools";
import { listCalendarEvents, createCalendarEvent } from "./calendarTools";
import { setVolume } from "./volumeTools";
import { operateScreen, pressButton } from "../operator";
import { notificationDigest } from "./notificationTools";

/**
 * The tool registry. Later phases (Gmail/Spotify APIs, accessibility agent,
 * MCP connectors, multi-agent research) plug in here.
 */
export const tools: Tool[] = [
  // device
  getDeviceStatus,
  openSettings,
  setBrightness,
  flashlight,
  clipboardTool,
  openApp,
  openDownloads,
  // comms
  findContact,
  whatsappMessage,
  openUrl,
  // media & files
  searchPhotos,
  shareFile,
  grantFolderAccess,
  searchFiles,
  manageFile,
  takePhoto,
  look,
  // phone actions
  getLocation,
  callNumber,
  setAlarm,
  setTimer,
  uninstallApp,
  setVolume,
  listCalendarEvents,
  createCalendarEvent,
  // agentic hands (screen control)
  operateScreen,
  pressButton,
  guideMe,
  // awareness
  notificationDigest,
  // persistent profile memory
  remember,
  recallProfile,
  forgetFact,
  // web & research
  webSearch,
  readWebpage,
  getWeather,
  deepResearch,
  dailyBriefing,
  // reminders
  scheduleReminder,
  listReminders,
  cancelReminder,
  // memory
  saveNote,
  listNotes,
  deleteNote,
];

export const toolByName = new Map(tools.map((t) => [t.definition.function.name, t]));

export const toolDefinitions: ToolDefinition[] = tools.map((t) => t.definition);
