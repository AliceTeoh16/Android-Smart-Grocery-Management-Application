import test from "node:test";
import assert from "node:assert/strict";
import express from "express";
import { readFileSync } from "node:fs";
import { matchInventoryCategory } from "./inventory-category-mapping.js";
import { registerIngredientScan, normalizeScannedIngredients, inventoryScanCapabilities } from "./ingredient-scan.js";

const image = { mime_type: "image/jpeg", image_base64: Buffer.from([255, 216, 255, 224, 1, 2, 3, 4]).toString("base64") };
const categoryCases = JSON.parse(readFileSync(new URL("../app/src/test/resources/inventory-category-cases.json", import.meta.url), "utf8"));
const unlistedCases = JSON.parse(readFileSync(new URL("../app/src/test/resources/unlisted-recognition-cases.json", import.meta.url), "utf8"));

test("unlisted product types in every category survive detection without a name allowlist", async () => {
  for (const item of unlistedCases) {
    assert.equal(matchInventoryCategory(item.name), null, `Must exercise a name outside local rules: ${item.name}`);
  }
  await withServer(async request => {
    const prompt = request.contents[0].parts[0].text;
    assert.match(prompt, /open-vocabulary recognition/);
    assert.match(prompt, /illustrative, not an exhaustive product catalog or allowlist/);
    for (const family of ["canned", "condiments", "kitchenware", "batteries", "skincare", "cosmetics", "menstrual care"]) {
      assert.ok(prompt.includes(family), family);
    }
    assert.match(prompt, /use a truthful generic product name/);
    return { text: JSON.stringify({ items: unlistedCases.map(item => ({ ...item, quantity: 1 })) }) };
  }, async post => {
    const response = await post(image);
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).items, unlistedCases.map(item => ({ ...item, quantity: 1 })));
  });
});

test("one malformed detection cannot discard the remaining valid products", async () => {
  await withServer(async () => ({ text: JSON.stringify({ items: [
    null, {}, { name: " " }, { name: "photo123.jpg" },
    { name: "Lipstick", category: "Personal Care", subcategory: null, quantity: 1 },
    { name: "Kombucha", category: "Food & Beverage", subcategory: "Beverages", quantity: null }
  ] }) }), async post => {
    const response = await post(image);
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).items.map(item => item.name), ["Lipstick", "Kombucha"]);
  });
  assert.deepEqual(normalizeScannedIngredients({ items: [{ name: "Unlisted product", category: "__proto__", subcategory: "constructor" }] }).items,
    [{ name: "Unlisted product", category: "Food & Beverage", subcategory: "Pantry", quantity: null }]);
});

test("photo mapping agrees with Android voice defaults for the shared multilingual product corpus", async () => {
  for (const { name, selection } of categoryCases) {
    assert.equal(matchInventoryCategory(name), selection, name);
    const category = ["Personal Care", "Household Essentials"].includes(selection) ? selection : "Food & Beverage";
    const subcategory = category === "Food & Beverage" ? selection : null;
    for (const input of [{ category: "Food & Beverage", subcategory: "Pantry" }, { category: "Invalid" }]) {
      assert.deepEqual(normalizeScannedIngredients({ items: [{ name, ...input, quantity: 2 }] }).items,
        [{ name, category, subcategory, quantity: 2 }], name);
    }
  }
  // Exercise the HTTP/model-response boundary as well as direct normalization.
  for (let start = 0; start < categoryCases.length; start += 20) {
    const batch = categoryCases.slice(start, start + 20);
    await withServer(async () => ({ text: JSON.stringify({ items: batch.map(({ name }) =>
      ({ name, category: "Food & Beverage", subcategory: "Pantry", quantity: 2 })) }) }), async post => {
      const response = await post(image);
      assert.equal(response.status, 200);
      const { items } = await response.json();
      assert.deepEqual(items.map(item => item.subcategory || item.category), batch.map(item => item.selection));
    });
  }
});

test("photo context and legacy labels survive when product names do not determine the category", () => {
  const items = [
    { name: "Chicken", category: "Food & Beverage", subcategory: "Frozen Foods" },
    { name: "Dragonfruit", category: "Food & Beverage", subcategory: " produce " },
    { name: "Unfamiliar product A", category: "toiletries", subcategory: "Dairy" },
    { name: "Unfamiliar product B", category: "Household Essentials", subcategory: "Pantry" },
    { name: "Unfamiliar product C", category: "Snacks" },
    { name: "Unfamiliar product D", category: "Invalid" }
  ];
  assert.deepEqual(normalizeScannedIngredients({ items }).items.map(item => item.subcategory || item.category),
    ["Frozen Foods", "Produce", "Personal Care", "Household Essentials", "Snacks & Sweets", "Pantry"]);
});
async function withServer(generate, block, options = {}) {
  const app = express();
  registerIngredientScan(app, { client: { models: { generateContent: generate } }, modelCandidates: ["primary", "fallback"], hasApiKey: () => true, ...options });
  // Verify new image middleware does not consume unrelated recipe requests.
  app.use(express.json({ limit: "1mb" }));
  app.post("/recipes/generate", (req, res) => res.json(req.body));
  const server = app.listen(0, "127.0.0.1");
  await new Promise(resolve => server.once("listening", resolve));
  const post = async (body, path = "/ingredients/scan") => fetch(`http://127.0.0.1:${server.address().port}${path}`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body)
  });
  const get = path => fetch(`http://127.0.0.1:${server.address().port}${path}`);
  try { await block(post, get); } finally { await new Promise(resolve => server.close(resolve)); }
}

test("sends image bytes to the model and returns review-only suggestions", async () => {
  await withServer(async request => {
    assert.equal(request.contents[0].parts[1].inlineData.data, image.image_base64);
    assert.equal(request.contents[0].parts[1].inlineData.mimeType, "image/jpeg");
    assert.equal(request.config.responseSchema.properties.items.items.properties.quantity.nullable, true);
    return { text: JSON.stringify({ items: [{ name: " Eggs ", category: "Dairy", quantity: 2 }, { name: "Rice", category: "Pantry", quantity: null }] }) };
  }, async post => {
    const response = await post(image);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { items: [{ name: "Eggs", category: "Food & Beverage", subcategory: "Dairy", quantity: 2 }, { name: "Rice", category: "Food & Beverage", subcategory: "Pantry", quantity: null }] });
  });
});

test("does not invent counts or accept price/expiry fields from AI", () => {
  assert.deepEqual(normalizeScannedIngredients({ items: [{ name: "Milk", category: "wrong", quantity: 1.5, price: 15, expiry_date: "tomorrow" }] }),
    { items: [{ name: "Milk", category: "Food & Beverage", subcategory: "Dairy", quantity: null }] });
  assert.deepEqual(normalizeScannedIngredients({ items: [{ name: "" }] }), { items: [] });
  assert.throws(() => normalizeScannedIngredients({}));
});

test("watermarks, filenames and generic labels never become food candidates", () => {
  const items = ["Veg_1_Front", "photo123.jpg", "Vegetable", "Food", "Broccoli", "Onion", "Green Bell Pepper", "Tomato", "Potato"]
    .map(name => ({ name, category: "Fruits & Vegetables", quantity: 1 }));
  assert.deepEqual(normalizeScannedIngredients({ items }).items.map(item => item.name),
    ["Broccoli", "Onion", "Green Bell Pepper", "Tomato", "Potato"]);
});

test("single food and several foods use the same batch response without text fields", async () => {
  for (const names of [["Broccoli"], ["Onion", "Green Bell Pepper", "Tomato", "Potato"]]) {
    await withServer(async () => ({ text: JSON.stringify({ items: names.map(name => ({ name, category: "Fruits & Vegetables", quantity: 1 })) }) }),
      async post => {
        const response = await post(image);
        assert.equal(response.status, 200);
        assert.deepEqual((await response.json()).items.map(item => item.name), names);
      });
  }
});

test("rejects malformed/non-image and oversized uploads before calling AI", async () => {
  let calls = 0;
  await withServer(async () => { calls++; }, async post => {
    for (const body of [{}, { ...image, mime_type: "text/plain" }, { ...image, image_base64: "abcd" },
      { ...image, image_base64: "!invalid!" }, { ...image, image_base64: "A".repeat(6 * 1024 * 1024) }]) {
      const response = await post(body);
      assert.ok([400, 413].includes(response.status));
    }
    assert.equal(calls, 0);
  });
});

test("empty photo results remain empty and recipes keep their request body", async () => {
  let calls = 0;
  await withServer(async () => { calls++; return { text: '{"items":[]}' }; }, async post => {
    assert.deepEqual(await (await post(image)).json(), { items: [] });
    assert.equal(calls, 2, "One recheck only; genuinely empty photos stay empty");
    const recipe = { user_prompt: "noodles for two", available_ingredients: [] };
    assert.deepEqual(await (await post(recipe, "/recipes/generate")).json(), recipe);
  });
});

test("an empty first pass rechecks the same image and recognizes unpackaged drinkware", async () => {
  const requests = [];
  await withServer(async request => {
    requests.push(request);
    const prompt = request.contents[0].parts[0].text;
    assert.match(prompt, /Empty teacups and mugs are supported Household Essentials/);
    assert.match(prompt, /do not guess tea or coffee merely because a cup is present/);
    return { text: JSON.stringify({ items: requests.length === 1 ? [] :
      [{ name: "Tea cup", category: "Food & Beverage", subcategory: "Beverages", quantity: 1 }] }) };
  }, async post => {
    const response = await post(image);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { items: [
      { name: "Tea cup", category: "Household Essentials", subcategory: null, quantity: 1 }
    ] });
  });
  assert.equal(requests.length, 2);
  assert.match(requests[1].contents[0].parts[0].text, /SECOND VISUAL CHECK/);
  assert.deepEqual(requests[0].contents[0].parts[1], requests[1].contents[0].parts[1]);
  assert.ok(requests.every(request => request.config.abortSignal instanceof AbortSignal));
});

test("successful detections need no recheck and cup contents stay separate from drinkware", async () => {
  let calls = 0;
  await withServer(async () => {
    calls++;
    return { text: JSON.stringify({ items: [
      { name: "Mug", category: "Household Essentials", subcategory: null, quantity: 1 },
      { name: "Cup of tea", category: "Food & Beverage", subcategory: "Beverages", quantity: 1 }
    ] }) };
  }, async post => {
    const { items } = await (await post(image)).json();
    assert.deepEqual(items.map(item => [item.category, item.subcategory]),
      [["Household Essentials", null], ["Food & Beverage", "Beverages"]]);
  });
  assert.equal(calls, 1);
});

test("a filtered filename-only result is rechecked without turning metadata into objects", async () => {
  let calls = 0;
  await withServer(async () => {
    calls++;
    return { text: JSON.stringify({ items: [{ name: calls === 1 ? "photo123.jpg" : "Teacup", quantity: 1 }] }) };
  }, async post => {
    const { items } = await (await post(image)).json();
    assert.equal(items.length, 1);
    assert.equal(items[0].name, "Teacup");
    assert.equal(items[0].category, "Household Essentials");
  });
  assert.equal(calls, 2);
});

test("empty-result recheck shares the scan deadline and never loops", async () => {
  let calls = 0;
  await withServer(request => {
    calls++;
    if (calls === 1) return { text: '{"items":[]}' };
    return new Promise((_, reject) => request.config.abortSignal.addEventListener("abort", () => reject(new Error("aborted"))));
  }, async post => {
    const response = await post(image);
    assert.equal(response.status, 504);
  }, { timeoutMs: 20 });
  assert.equal(calls, 2);
});

test("a stalled model falls back without waiting for the overall scan deadline", async () => {
  const models = [];
  await withServer(async request => {
    models.push(request.model);
    if (request.model === "primary") return new Promise(() => {}); // Provider ignores abort.
    return { text: '{"items":[{"name":"Lipstick","category":"Personal Care","subcategory":null,"quantity":1}]}' };
  }, async post => {
    const response = await post(image);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).items[0].category, "Personal Care");
  }, { timeoutMs: 200, modelTimeoutMs: 20 });
  assert.deepEqual(models, ["primary", "fallback"]);
});

test("an SDK abort rejection during a model timeout still triggers fallback", async () => {
  const models = [];
  await withServer(request => {
    models.push(request.model);
    if (request.model === "primary") return new Promise((_, reject) => {
      request.config.abortSignal.addEventListener("abort", () => reject(Object.assign(new Error("Aborted"), { name: "AbortError", code: 20 })));
    });
    return { text: '{"items":[{"name":"Soap","category":"Personal Care","subcategory":null,"quantity":1}]}' };
  }, async post => {
    const response = await post(image);
    assert.equal(response.status, 200);
  }, { timeoutMs: 200, modelTimeoutMs: 20 });
  assert.deepEqual(models, ["primary", "fallback"]);
});

test("an empty-result recheck starts with the model that actually responded", async () => {
  const models = [];
  await withServer(async request => {
    models.push(request.model);
    if (request.model === "primary") throw Object.assign(new Error("unavailable"), { status: 503 });
    return { text: JSON.stringify({ items: models.length === 2 ? [] : [{ name: "Mug", category: "Household Essentials", quantity: 1 }] }) };
  }, async post => {
    assert.equal((await post(image)).status, 200);
  });
  assert.deepEqual(models, ["primary", "fallback", "fallback"]);
});

test("the overall deadline completes even when every model ignores abort", async () => {
  await withServer(() => new Promise(() => {}), async post => {
    const response = await post(image);
    assert.equal(response.status, 504);
  }, { timeoutMs: 35, modelTimeoutMs: 20 });
});

test("reports missing setup, retries an unavailable model, and handles invalid AI output", async () => {
  await withServer(async () => { throw new Error("must not call"); }, async post => {
    assert.equal((await post(image)).status, 503);
  }, { hasApiKey: () => false });
  const models = [];
  await withServer(async request => {
    models.push(request.model);
    if (request.model === "primary") throw Object.assign(new Error("not found"), { status: 404 });
    return { text: '{"items":[{"name":"Tomato","category":"Food & Beverage","subcategory":"Produce","quantity":1}]}' };
  }, async post => { assert.equal((await post(image)).status, 200); });
  assert.deepEqual(models, ["primary", "fallback"]);
  await withServer(async () => ({ text: "not JSON" }), async post => { assert.equal((await post(image)).status, 502); });
});

test("times out the model and returns actionable retry guidance", async () => {
  await withServer(request => new Promise((_, reject) => {
    request.config.abortSignal.addEventListener("abort", () => reject(new Error("aborted")));
  }), async post => {
    const response = await post(image);
    assert.equal(response.status, 504);
    assert.match((await response.json()).error, /timed out/);
  }, { timeoutMs: 20 });
});


test("mixed food and non-food products use a hybrid category hierarchy", async () => {
  await withServer(async request => {
    const properties = request.config.responseSchema.properties.items.items.properties;
    assert.deepEqual(properties.category.enum, ["Food & Beverage", "Household Essentials", "Personal Care"]);
    assert.equal(properties.subcategory.nullable, true);
    assert.match(request.contents[0].parts[0].text, /shampoo/);
    return { text: JSON.stringify({ items: [
      { name: "Tomato", category: "Food & Beverage", subcategory: "Produce", quantity: 3 },
      { name: "Shampoo", category: "Food & Beverage", subcategory: "Dairy", quantity: 1 },
      { name: "Dishwashing detergent", category: "Household Essentials", subcategory: "Pantry", quantity: null },
      { name: "Cumin", category: "Food & Beverage", subcategory: "Spices", quantity: 1 }
    ] }) };
  }, async post => {
    assert.deepEqual(await (await post(image)).json(), { items: [
      { name: "Tomato", category: "Food & Beverage", subcategory: "Produce", quantity: 3 },
      { name: "Shampoo", category: "Personal Care", subcategory: null, quantity: 1 },
      { name: "Dishwashing detergent", category: "Household Essentials", subcategory: null, quantity: null },
      { name: "Cumin", category: "Food & Beverage", subcategory: "Spices", quantity: 1 }
    ] });
  });
});

test("household-only cleaning tools are requested and retained without food or subcategories", async () => {
  const names = ["Rubber gloves", "Garbage bags", "Microfiber cloths", "Dustpan", "Scrub brush", "Toilet brush", "Wastebin", "Plunger"];
  await withServer(async request => {
    const prompt = request.contents[0].parts[0].text;
    assert.match(prompt, /food does not need to be present/i);
    for (const name of ["rubber gloves", "garbage bags", "dustpans", "toilet brushes", "wastebins", "plungers"]) {
      assert.ok(prompt.includes(name));
    }
    assert.match(prompt, /packaged multipack as one package/i);
    // Even legacy/invalid food labels must not move recognizable cleaning tools into food.
    return { text: JSON.stringify({ items: names.map(name => ({ name, category: "Other", subcategory: "Pantry", quantity: null })) }) };
  }, async post => {
    const response = await post(image);
    assert.equal(response.status, 200);
    const { items } = await response.json();
    assert.deepEqual(items.map(item => item.name), names);
    assert.ok(items.every(item => item.category === "Household Essentials" && item.subcategory === null && item.quantity === null));
  });
});

test("scanner capabilities identify the loaded backend without sending images to AI", async () => {
  await withServer(async () => { throw new Error("must not call AI"); }, async (_post, get) => {
    const response = await get("/ingredients/scan/capabilities");
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), inventoryScanCapabilities);
    assert.equal(inventoryScanCapabilities.revision, "household-inventory-v6");
    assert.equal(inventoryScanCapabilities.householdCleaningTools, true);
    assert.equal(inventoryScanCapabilities.householdDrinkware, true);
    assert.equal(inventoryScanCapabilities.emptyResultRecheck, true);
    assert.equal(inventoryScanCapabilities.openVocabulary, true);
    assert.equal(inventoryScanCapabilities.modelTimeoutFallback, true);
    assert.equal(inventoryScanCapabilities.foodSubcategories.length, 9);
    assert.deepEqual(inventoryScanCapabilities.categories, ["Food & Beverage", "Household Essentials", "Personal Care"]);
  }, { hasApiKey: () => false });
});

test("all nine food groups reach the model schema and the three new groups survive normalization", async () => {
  const expected = ["Produce", "Meat & Seafood", "Dairy", "Bakery", "Pantry", "Spices", "Beverages", "Snacks & Sweets", "Frozen Foods"];
  await withServer(async request => {
    assert.deepEqual(request.config.responseSchema.properties.items.items.properties.subcategory.enum, expected);
    const prompt = request.contents[0].parts[0].text;
    assert.match(prompt, /juices belong to Beverages/);
    assert.match(prompt, /nuts belong to Snacks & Sweets/);
    assert.match(prompt, /frozen meat belong to Frozen Foods/);
    assert.doesNotMatch(prompt, /Beverages and snacks belong to Pantry/);
    return { text: JSON.stringify({ items: [
      { name: "Coffee", category: "Food & Beverage", subcategory: "Beverages", quantity: 1 },
      { name: "Biscuits", category: "Food & Beverage", subcategory: "Snacks & Sweets", quantity: 2 },
      { name: "Ice cream", category: "Food & Beverage", subcategory: "Frozen Foods", quantity: 1 },
      { name: "Chocolate", category: "Snacks", quantity: 1 },
      { name: "Juice", category: "Beverages", quantity: null }
    ] }) };
  }, async post => {
    const response = await post(image);
    assert.equal(response.status, 200);
    const { items } = await response.json();
    assert.deepEqual(items.map(item => item.subcategory), ["Beverages", "Snacks & Sweets", "Frozen Foods", "Snacks & Sweets", "Beverages"]);
    assert.ok(items.every(item => item.category === "Food & Beverage"));
  });
});
