import * as Location from "expo-location";
import type { Tool } from "../types";

const UA =
  "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36";

function decodeEntities(s: string): string {
  return s
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#x27;|&#39;/g, "'")
    .replace(/&nbsp;/g, " ");
}

export const webSearch: Tool = {
  definition: {
    type: "function",
    function: {
      name: "web_search",
      description:
        "Search the web and get the top results (title, url, snippet). Use for current events, " +
        "facts you're unsure about, prices, news — anything needing live information.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "The search query" },
        },
        required: ["query"],
      },
    },
  },
  describeCall: (args) => `Search web: "${args.query}"`,
  execute: async (args) => {
    const res = await fetch(
      `https://html.duckduckgo.com/html/?q=${encodeURIComponent(String(args.query))}`,
      { headers: { "User-Agent": UA } }
    );
    if (!res.ok) return { ok: false, output: `Search failed (HTTP ${res.status})` };
    const html = await res.text();

    const results: { title: string; url: string; snippet: string }[] = [];
    const linkRe = /class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
    const snippetRe = /class="result__snippet"[^>]*>([\s\S]*?)<\/a>/g;
    const snippets: string[] = [];
    let m: RegExpExecArray | null;
    while ((m = snippetRe.exec(html)) && snippets.length < 6) {
      snippets.push(decodeEntities(m[1].replace(/<[^>]+>/g, "")).trim());
    }
    while ((m = linkRe.exec(html)) && results.length < 5) {
      let url = m[1];
      const uddg = /uddg=([^&]+)/.exec(url);
      if (uddg) url = decodeURIComponent(uddg[1]);
      results.push({
        title: decodeEntities(m[2].replace(/<[^>]+>/g, "")).trim(),
        url,
        snippet: snippets[results.length] ?? "",
      });
    }
    if (!results.length) return { ok: false, output: "No results parsed — try rewording." };
    return { ok: true, output: results };
  },
};

export const readWebpage: Tool = {
  definition: {
    type: "function",
    function: {
      name: "read_webpage",
      description:
        "Fetch a webpage and return its readable text (max ~3500 chars). " +
        "Use after web_search to read a promising result.",
      parameters: {
        type: "object",
        properties: {
          url: { type: "string", description: "The page URL" },
        },
        required: ["url"],
      },
    },
  },
  describeCall: (args) => `Read ${args.url}`,
  execute: async (args) => {
    const res = await fetch(String(args.url), { headers: { "User-Agent": UA } });
    if (!res.ok) return { ok: false, output: `Fetch failed (HTTP ${res.status})` };
    const html = await res.text();
    const text = decodeEntities(
      html
        .replace(/<script[\s\S]*?<\/script>/gi, " ")
        .replace(/<style[\s\S]*?<\/style>/gi, " ")
        .replace(/<[^>]+>/g, " ")
        .replace(/\s+/g, " ")
    ).trim();
    return { ok: true, output: text.slice(0, 3500) };
  },
};

const WEATHER_CODES: Record<number, string> = {
  0: "clear sky", 1: "mostly clear", 2: "partly cloudy", 3: "overcast",
  45: "fog", 48: "icy fog", 51: "light drizzle", 53: "drizzle", 55: "heavy drizzle",
  61: "light rain", 63: "rain", 65: "heavy rain", 66: "freezing rain", 67: "heavy freezing rain",
  71: "light snow", 73: "snow", 75: "heavy snow", 77: "snow grains",
  80: "light showers", 81: "showers", 82: "violent showers",
  85: "snow showers", 86: "heavy snow showers",
  95: "thunderstorm", 96: "thunderstorm with hail", 99: "severe thunderstorm with hail",
};

export const getWeather: Tool = {
  definition: {
    type: "function",
    function: {
      name: "get_weather",
      description:
        "Get current weather and today's forecast. Provide a place name, or omit it to use " +
        "the phone's current location.",
      parameters: {
        type: "object",
        properties: {
          place: { type: "string", description: "City/place name. Omit for current location." },
        },
      },
    },
  },
  describeCall: (args) => `Weather ${args.place ? `in ${args.place}` : "here"}`,
  execute: async (args) => {
    let lat: number, lon: number, label: string;
    if (args.place) {
      const geo = await fetch(
        `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(String(args.place))}&count=1`
      ).then((r) => r.json());
      const hit = geo?.results?.[0];
      if (!hit) return { ok: false, output: `Couldn't find "${args.place}".` };
      lat = hit.latitude; lon = hit.longitude;
      label = `${hit.name}${hit.country ? ", " + hit.country : ""}`;
    } else {
      const { status } = await Location.requestForegroundPermissionsAsync();
      if (status !== "granted") {
        return { ok: false, output: "Location permission denied — ask for weather in a named place instead." };
      }
      const pos = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Low });
      lat = pos.coords.latitude; lon = pos.coords.longitude;
      label = "current location";
    }
    const wx = await fetch(
      `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
        `&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m` +
        `&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1`
    ).then((r) => r.json());
    const c = wx.current;
    return {
      ok: true,
      output: {
        place: label,
        now: {
          temperatureC: c.temperature_2m,
          feelsLikeC: c.apparent_temperature,
          humidityPct: c.relative_humidity_2m,
          windKmh: c.wind_speed_10m,
          sky: WEATHER_CODES[c.weather_code] ?? `code ${c.weather_code}`,
        },
        today: {
          maxC: wx.daily.temperature_2m_max[0],
          minC: wx.daily.temperature_2m_min[0],
          rainChancePct: wx.daily.precipitation_probability_max[0],
        },
      },
    };
  },
};
