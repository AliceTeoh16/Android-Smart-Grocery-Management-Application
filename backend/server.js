import cors from "cors";
import dotenv from "dotenv";
import express from "express";
import { GoogleGenAI, Type } from "@google/genai";
import { registerIngredientScan, inventoryScanCapabilities } from "./ingredient-scan.js";
import { registerVoiceTranscription } from "./voice-transcription.js";
import { cookingFields, cookingPrompt, normalizeCookingFields, generateValidatedChecklist } from "./checklist-steps.js";

dotenv.config();

const app = express();
const port = Number(process.env.PORT || 8787);
const primaryModel = process.env.GEMINI_MODEL || "gemini-3.5-flash-lite";
const fallbackModels = (
  process.env.GEMINI_FALLBACK_MODELS ||
  "gemini-3.5-flash-lite,gemini-3.1-flash-lite,gemini-flash-lite-latest,gemini-3.8-flash"
)
  .split(",")
  .map((value) => value.trim())
  .filter(Boolean);
const modelCandidates = [...new Set([primaryModel, ...fallbackModels])];

app.use(cors());

const client = new GoogleGenAI({
  apiKey: process.env.GEMINI_API_KEY
});

// The image route has its own upload limit; recipe requests keep their existing limit.
registerIngredientScan(app, { client, modelCandidates });
registerVoiceTranscription(app, { client, modelCandidates });
app.use(express.json({ limit: "1mb" }));

const checklistResponseSchema = {
  type: Type.OBJECT,
  required: ["dish_name", "servings", "missing_checklist", "inventory_deducted", "ingredients", "steps", "step_ingredients"],
  propertyOrdering: ["dish_name", "servings", "missing_checklist", "inventory_deducted", "ingredients", "steps", "step_ingredients"],
  properties: {
    ...cookingFields,
    dish_name: { type: Type.STRING },
    servings: { type: Type.INTEGER, minimum: 1, maximum: 20 },
    missing_checklist: {
      type: Type.ARRAY,
      items: {
        type: Type.OBJECT,
        required: ["name", "missing_qty", "category"],
        propertyOrdering: ["name", "missing_qty", "category"],
        properties: {
          name: { type: Type.STRING },
          missing_qty: { type: Type.STRING },
          category: { type: Type.STRING }
        }
      }
    },
    inventory_deducted: {
      type: Type.ARRAY,
      items: {
        type: Type.OBJECT,
        required: ["name", "qty_used"],
        propertyOrdering: ["name", "qty_used"],
        properties: {
          name: { type: Type.STRING },
          qty_used: { type: Type.STRING }
        }
      }
    }
  }
};

app.get("/health", (_req, res) => {
  res.json({ ok: true, provider: "gemini", model: primaryModel, fallbackModels, mode: "checklist_assistant",
    inventoryScan: inventoryScanCapabilities });
});

app.post("/recipes/generate", async (req, res) => {
  if (!process.env.GEMINI_API_KEY) {
    res.status(500).json({ error: "GEMINI_API_KEY is not configured on the backend." });
    return;
  }

  const body = req.body || {};
  const availableIngredients = Array.isArray(body.available_ingredients)
    ? body.available_ingredients
    : [];
  const userPrompt = typeof body.user_prompt === "string" ? body.user_prompt.trim() : "";

  if (!userPrompt) {
    res.status(400).json({ error: "user_prompt is required for checklist generation." });
    return;
  }

  const currentInventory = toGeminiInventory(availableIngredients);
  console.log(
    `[checklist] Gemini request prompt="${userPrompt}" inventory=${JSON.stringify(currentInventory)}`
  );

  try {
    const checklistDeadline = Date.now() + 45_000;
    const parsed = await withTimeout(
      generateValidatedChecklist(correction => generateChecklistJson(currentInventory, userPrompt, correction), normalizeChecklist,
        raw => JSON.parse(extractJsonText(raw)), () => Date.now() < checklistDeadline && !res.destroyed),
      45_000,
      "Gemini checklist generation timed out."
    );
    res.json(parsed);
  } catch (error) {
    console.error("Gemini checklist generation failed:", error);
    const status = inferGeminiStatus(error);
    const detail = error?.code === "CHECKLIST_RESPONSE_INVALID"
      ? "The AI could not produce a consistent ingredient list and recipe steps. Please try generating again."
      : error instanceof Error ? error.message : "Unknown error";

    res.status(status).json({
      error: "AI checklist generation failed.",
      detail,
      code: error?.code || error?.status || null
    });
  }
});

async function generateChecklistJson(currentInventory, userPrompt, correction = "") {
  const prompt = buildChecklistPrompt(currentInventory, userPrompt) + (correction ? `\n\nCorrection required:\n${correction}` : "");
  let lastError;

  for (const candidateModel of modelCandidates) {
    try {
      console.log(`[checklist] Trying Gemini model: ${candidateModel}`);
      const response = await client.models.generateContent({
        model: candidateModel,
        contents: prompt,
        config: {
          responseMimeType: "application/json",
          responseSchema: checklistResponseSchema,
          temperature: 0.2,
          maxOutputTokens: 6144
        }
      });

      return response.text;
    } catch (error) {
      lastError = error;
      if (!isRetryableGeminiError(error)) {
        throw error;
      }
      console.warn(`Gemini model ${candidateModel} failed with a retryable error. Trying fallback if available.`);
    }
  }

  throw lastError;
}

function buildChecklistPrompt(currentInventory, userPrompt) {
  const systemPrompt = [
    "You are a culinary ingredient assistant and grocery checklist calculator.",
    "Your output should feel like a concise AI Overview: one clear dish summary through dish_name and servings, then exact missing grocery items.",
    "Return one JSON payload containing the grocery checklist, complete recipe ingredients and synchronized cooking steps.",
    "",
    "Rules:",
    "1. Extract the food title as dish_name.",
    "2. Extract serving count as servings. If the user does not specify servings, infer 2.",
    "3. Preserve the user's requested dish. Do not rewrite, substitute, or invent a different dish based on inventory.",
    "4. Inventory is only for deduction. If a required item is not in inventory, list it as missing.",
    "5. Retrieve all standard ingredients and seasonings required for this meal scaled to the serving count.",
    "6. Use exact numeric quantities with standard units: pcs, g, ml, tbsp, tsp, stalks, cloves, slices.",
    "7. Do not use vague quantities such as As needed, Some, Optional, a little, enough, to taste, shi liang, shao xu, or blank.",
    "8. Compare the required ingredient list against Current Inventory.",
    "9. Deduct in-stock items. Fully covered items go into inventory_deducted.",
    "10. Missing or partially missing items go into missing_checklist with name, missing_qty, and category.",
    "11. Ignore non-food words: people, person, persons, serving, servings, pax, for, for 4, ren, ge ren, chi, wo yao, cook.",
    "12. Never include people/person/serving words as ingredients.",
    "13. Include the cooking instructions for this exact ingredient plan in the same response.",
    cookingPrompt,
    "",
    "Example output shape:",
    JSON.stringify(
      {
        dish_name: "Tomato Beef Noodles",
        servings: 4,
        missing_checklist: [
          {
            name: "Beef (牛肉)",
            missing_qty: "500g",
            category: "Meat"
          },
          {
            name: "Noodles (面条)",
            missing_qty: "400g",
            category: "Pantry"
          }
        ],
        inventory_deducted: [
          {
            name: "Tomato (番茄)",
            qty_used: "4 pcs (Fully Covered)"
          }
        ],
        ingredients: [
          { name: "Beef (牛肉)", amount: "500g", is_in_inventory: false },
          { name: "Noodles (面条)", amount: "400g", is_in_inventory: false },
          { name: "Tomato (番茄)", amount: "4 pcs", is_in_inventory: true }
        ],
        steps: [
          "Slice the beef and tomatoes. Cook the tomatoes in a non-stick pan, then add the beef and cook through.",
          "Add the fresh noodles and toss until cooked and hot."
        ],
        step_ingredients: [["Beef (牛肉)", "Tomato (番茄)"], ["Noodles (面条)"]]
      },
      null,
      2
    )
  ].join("\n");

  const userContent = `User Request: "${userPrompt}"\nCurrent Inventory: ${JSON.stringify(currentInventory)}`;
  return `${systemPrompt}\n\n${userContent}`;
}

function toGeminiInventory(availableIngredients) {
  return availableIngredients
    .map((ingredient) => ({
      name: String(ingredient.name || "").trim(),
      qty: Number(ingredient.quantity || 0),
      unit: ingredient.unit || "pcs"
    }))
    .filter((ingredient) => ingredient.name);
}

function normalizeChecklist(value) {
  if (!textOrFallback(value?.dish_name, "")) {
    throw new Error("Gemini response missing required field: dish_name.");
  }
  if (!Number.isInteger(Number(value?.servings)) || Number(value.servings) <= 0) {
    throw new Error("Gemini response missing valid required field: servings.");
  }
  if (!Array.isArray(value?.missing_checklist)) {
    throw new Error("Gemini response missing required array: missing_checklist.");
  }
  if (!Array.isArray(value?.inventory_deducted)) {
    throw new Error("Gemini response missing required array: inventory_deducted.");
  }

  const missingChecklist = value.missing_checklist.map((item, index) => {
    const normalizedItem = {
      name: textOrFallback(item.name, ""),
      missing_qty: textOrFallback(item.missing_qty, ""),
      category: textOrFallback(item.category, "Other")
    };

    if (!normalizedItem.name || !normalizedItem.missing_qty) {
      throw new Error(`Gemini response missing required missing_checklist fields at index ${index}.`);
    }
    if (isServingWord(normalizedItem.name)) {
      throw new Error(`Gemini response incorrectly returned serving words as an ingredient: ${normalizedItem.name}.`);
    }
    if (isVagueQuantity(normalizedItem.missing_qty)) {
      throw new Error(`Gemini response returned a vague quantity for ${normalizedItem.name}.`);
    }

    return normalizedItem;
  });

  const inventoryDeducted = value.inventory_deducted.map((item, index) => {
    const normalizedItem = {
      name: textOrFallback(item.name, ""),
      qty_used: textOrFallback(item.qty_used, "")
    };

    if (!normalizedItem.name || !normalizedItem.qty_used) {
      throw new Error(`Gemini response missing required inventory_deducted fields at index ${index}.`);
    }

    return normalizedItem;
  });

  return {
    dish_name: value.dish_name.trim(),
    servings: Number(value.servings),
    missing_checklist: missingChecklist,
    inventory_deducted: inventoryDeducted,
    ...normalizeCookingFields(value, missingChecklist, inventoryDeducted)
  };
}

function extractJsonText(text) {
  const raw = String(text || "").trim();
  const fenced = raw.match(/```(?:json)?\s*([\s\S]*?)\s*```/i);
  if (fenced?.[1]) {
    return fenced[1].trim();
  }

  const firstBrace = raw.indexOf("{");
  const lastBrace = raw.lastIndexOf("}");
  if (firstBrace >= 0 && lastBrace > firstBrace) {
    return raw.slice(firstBrace, lastBrace + 1);
  }

  throw new Error("Gemini response did not contain JSON.");
}

function withTimeout(promise, timeoutMs, message) {
  let timeoutId;
  const timeout = new Promise((_, reject) => {
    timeoutId = setTimeout(() => reject(new Error(message)), timeoutMs);
  });

  return Promise.race([promise, timeout]).finally(() => clearTimeout(timeoutId));
}

function textOrFallback(value, fallback) {
  return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function isServingWord(value) {
  return /^(people|person|persons|serving|servings|pax|for|for\s+\d+|ren|ge ren|chi)$/i.test(String(value || "").trim());
}

function isVagueQuantity(value) {
  return /^(as needed|some|optional|a little|enough|to taste|shi liang|shao xu)$/i.test(String(value || "").trim());
}

function inferGeminiStatus(error) {
  const message = error instanceof Error ? error.message.toLowerCase() : "";
  const status = Number(error?.status || error?.code);

  if (Number.isInteger(status) && status >= 400 && status < 600) {
    return status;
  }

  if (
    message.includes("quota") ||
    message.includes("billing") ||
    message.includes("credits") ||
    message.includes("payment")
  ) {
    return 402;
  }

  if (message.includes("api key") || message.includes("permission") || message.includes("unauthorized")) {
    return 401;
  }

  return 502;
}

function isRetryableGeminiError(error) {
  const message = error instanceof Error ? error.message.toLowerCase() : "";
  const status = Number(error?.status || error?.code);

  return (
    status === 429 ||
    status === 404 ||
    status === 500 ||
    status === 502 ||
    status === 503 ||
    status === 504 ||
    message.includes("unavailable") ||
    message.includes("no longer available") ||
    message.includes("high demand") ||
    message.includes("temporarily") ||
    message.includes("timeout")
  );
}

app.listen(port, () => {
  console.log(
    `Smart Grocery checklist backend listening on http://localhost:${port} using Gemini (${modelCandidates.join(", ")})`
  );
});
