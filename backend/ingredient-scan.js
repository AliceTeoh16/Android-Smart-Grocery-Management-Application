import express from "express";
import { Type } from "@google/genai";
import { matchInventoryCategory } from "./inventory-category-mapping.js";
import { inventoryScanPrompt, emptyScanRecheck } from "./inventory-scan-prompt.js";

export const groceryCategories = ["Food & Beverage", "Household Essentials", "Personal Care"];
export const foodSubcategories = ["Produce", "Meat & Seafood", "Dairy", "Bakery", "Pantry", "Spices", "Beverages", "Snacks & Sweets", "Frozen Foods"];
export const inventoryScanCapabilities = Object.freeze({
  revision: "household-inventory-v6",
  categories: groceryCategories,
  foodSubcategories,
  householdCleaningTools: true,
  householdDrinkware: true,
  emptyResultRecheck: true,
  openVocabulary: true,
  modelTimeoutFallback: true
});
const categoryAliases = new Map(Object.entries({ household: "Household Essentials", cleaning: "Household Essentials", toiletries: "Personal Care" }));
const subcategoryAliases = new Map(Object.entries({ "fruits & vegetables": "Produce", fruit: "Produce", vegetables: "Produce",
  meat: "Meat & Seafood", seafood: "Meat & Seafood", seasoning: "Spices", seasonings: "Spices", spice: "Spices",
  beverage: "Beverages", drinks: "Beverages", snack: "Snacks & Sweets", snacks: "Snacks & Sweets", sweets: "Snacks & Sweets",
  frozen: "Frozen Foods", "frozen food": "Frozen Foods", other: "Pantry" }));
const canonical = (values, value) => typeof value === "string"
  ? values.find(candidate => candidate.toLowerCase() === value.trim().toLowerCase()) : null;
const legacySubcategory = value => canonical(foodSubcategories, value)
  || subcategoryAliases.get(typeof value === "string" ? value.trim().toLowerCase() : "") || null;
function classify(item, name) {
  const known = matchInventoryCategory(name);
  const suppliedCategory = canonical(groceryCategories, item.category)
    || categoryAliases.get(typeof item.category === "string" ? item.category.trim().toLowerCase() : "");
  const category = groceryCategories.slice(1).includes(known) ? known : suppliedCategory || "Food & Beverage";
  if (category !== "Food & Beverage") return { category, subcategory: null };
  const supplied = canonical(foodSubcategories, item.category) || legacySubcategory(item.subcategory) || legacySubcategory(item.category);
  return { category, subcategory: known === "Frozen Foods" || supplied === "Frozen Foods"
    ? "Frozen Foods" : known || supplied || "Pantry" };
}
const maxBytes = 4 * 1024 * 1024;
const schema = {
  type: Type.OBJECT,
  required: ["items"],
  properties: {
    items: {
      type: Type.ARRAY,
      maxItems: 20,
      items: {
        type: Type.OBJECT,
        required: ["name", "category", "subcategory", "quantity"],
        properties: {
          name: { type: Type.STRING },
          category: { type: Type.STRING, enum: groceryCategories },
          subcategory: { type: Type.STRING, enum: foodSubcategories, nullable: true },
          quantity: { type: Type.INTEGER, nullable: true, minimum: 1, maximum: 999 }
        }
      }
    }
  }
};

export function normalizeScannedIngredients(value) {
  if (!Array.isArray(value?.items)) throw new Error("Invalid ingredient scan response.");
  return {
    items: value.items.slice(0, 20).map(item => {
      const name = typeof item?.name === "string" ? item.name.trim().slice(0, 100) : "";
      // Preserve valid detections when one model row is missing a useful name.
      if (!name) return null;
      if (/^(?:food|vegetables?|produce|ingredients?|image|photo)$/i.test(name) ||
          /^(?:veg|image|img|photo|dsc|untitled)[_-]?\d|_front$|\.(?:jpe?g|png|webp)$/i.test(name)) return null;
      return {
        name,
        ...classify(item, name),
        quantity: Number.isInteger(item.quantity) && item.quantity > 0 && item.quantity <= 999 ? item.quantity : null
      };
    }).filter(Boolean).filter((item, index, items) => items.findIndex(other => other.name.toLowerCase() === item.name.toLowerCase()) === index)
  };
}

function validateImage(body) {
  const mimeType = body?.mime_type;
  const data = body?.image_base64;
  if (!["image/jpeg", "image/png", "image/webp"].includes(mimeType) ||
      typeof data !== "string" || !data || data.length > Math.ceil(maxBytes / 3) * 4 ||
      data.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) return null;
  const bytes = Buffer.from(data, "base64");
  const matches = mimeType === "image/jpeg" ? bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
    : mimeType === "image/png" ? bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
    : bytes.toString("ascii", 0, 4) === "RIFF" && bytes.toString("ascii", 8, 12) === "WEBP";
  return bytes.length > 0 && bytes.length <= maxBytes && matches ? { mimeType, data } : null;
}

/** A separate route and schema; existing recipe generation and grocery writes are untouched. */
export function registerIngredientScan(app, { client, modelCandidates, hasApiKey = () => Boolean(process.env.GEMINI_API_KEY), timeoutMs = 45000, modelTimeoutMs = 20000 }) {
  app.get("/ingredients/scan/capabilities", (_req, res) => res.json(inventoryScanCapabilities));
  app.post("/ingredients/scan", express.json({ limit: "6mb" }), async (req, res) => {
    const image = validateImage(req.body);
    if (!image) return res.status(400).json({ error: "Choose a JPEG, PNG, or WebP image smaller than 4 MB." });
    if (!hasApiKey()) return res.status(503).json({ error: "Ingredient scanning is not configured. Set GEMINI_API_KEY on the backend." });
    const abort = new AbortController();
    const deadline = Date.now() + timeoutMs;
    const timer = setTimeout(() => abort.abort(), timeoutMs);
    try {
      const primaryPrompt = inventoryScanPrompt({ categories: groceryCategories, foodSubcategories });
      let preferredModel;
      async function generate(model, prompt) {
        const requestAbort = new AbortController();
        const budget = Math.min(modelTimeoutMs, deadline - Date.now());
        const timeout = Object.assign(new Error("Inventory scan model timed out"), { name: "InventoryScanTimeout", status: 504 });
        if (abort.signal.aborted || budget <= 0) throw timeout;
        let requestTimer;
        let onAbort;
        const expired = new Promise((_, reject) => {
          // Settle our timeout first; an SDK AbortError must not hide the retryable 504.
          onAbort = () => { reject(timeout); requestAbort.abort(); };
          abort.signal.addEventListener("abort", onAbort, { once: true });
          requestTimer = setTimeout(onAbort, budget);
        });
        try {
          return await Promise.race([expired, client.models.generateContent({
            model,
            contents: [{ role: "user", parts: [{ text: prompt }, { inlineData: image }] }],
            config: { responseMimeType: "application/json", responseSchema: schema, temperature: 0.1,
              maxOutputTokens: 2048, abortSignal: requestAbort.signal, httpOptions: { timeout: budget } }
          })]);
        } finally {
          clearTimeout(requestTimer);
          abort.signal.removeEventListener("abort", onAbort);
        }
      }
      async function identify(prompt) {
        let response;
        const models = preferredModel ? [preferredModel, ...modelCandidates.filter(model => model !== preferredModel)] : modelCandidates;
        for (const model of models) {
          try {
            response = await generate(model, prompt);
            preferredModel = model;
            break;
          } catch (error) {
            const status = Number(error?.status || error?.code);
            if (abort.signal.aborted || ![404, 429, 500, 502, 503, 504].includes(status) || model === models.at(-1)) throw error;
          }
        }
        const text = String(response?.text || "").trim().replace(/^```(?:json)?\s*|\s*```$/g, "");
        return normalizeScannedIngredients(JSON.parse(text));
      }
      const first = await identify(primaryPrompt);
      if (first.items.length) return res.json(first);
      // Detection happens before category mapping. Recheck the pixels once rather than
      // manufacturing candidates from the filename or returning an avoidable empty list.
      const rechecked = await identify(primaryPrompt + " " + emptyScanRecheck);
      res.json(rechecked);
    } catch (error) {
      const status = Number(error?.status || error?.code);
      const timedOut = abort.signal.aborted || error?.name === "InventoryScanTimeout";
      const message = timedOut ? "The scan timed out. Try again or enter the item manually."
        : status === 429 ? "Ingredient scanning is busy or its quota is exhausted. Try again later."
        : "Could not identify items. Try a clearer photo or enter the item manually.";
      res.status(timedOut ? 504 : status === 429 ? 429 : 502).json({ error: message });
    } finally {
      clearTimeout(timer);
    }
  }, (error, _req, res, _next) => {
    res.status(error.type === "entity.too.large" ? 413 : 400).json({ error: "Choose a valid image smaller than 4 MB." });
  });
}
