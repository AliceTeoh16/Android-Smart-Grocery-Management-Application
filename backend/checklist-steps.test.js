import test from "node:test";
import assert from "node:assert/strict";
import { cookingFields, cookingPrompt, normalizeCookingFields, generateValidatedChecklist } from "./checklist-steps.js";

const missing = [{ name: "Cream Cheese", missing_qty: "400g", category: "Dairy" },
  { name: "Granulated Sugar", missing_qty: "100g", category: "Pantry" }];
const covered = [{ name: "Eggs", qty_used: "2 pcs (Fully Covered)" }];
const recipe = () => ({ ingredients: [
  { name: "Cream Cheese", amount: "400g", is_in_inventory: false },
  { name: "Granulated Sugar", amount: "100g", is_in_inventory: false },
  { name: "Eggs", amount: "2 pcs", is_in_inventory: true }
], steps: ["Step 1: Beat the cream cheese until smooth.", "Add granulated sugar and beat in the eggs.", "Bake, then cool."],
step_ingredients: [["Cream Cheese"], ["Granulated Sugar", "Eggs"], []] });

test("schema and prompt request checklist and cooking instructions in the same payload", () => {
  assert.ok(cookingFields.ingredients); assert.ok(cookingFields.steps); assert.ok(cookingFields.step_ingredients);
  assert.match(cookingPrompt, /same plan/); assert.match(cookingPrompt, /Do not suggest unlisted extras/);
});
test("one recipe retains exact amounts, stock status, ordered steps and equipment-only steps", () => {
  const result = normalizeCookingFields(recipe(), missing, covered);
  assert.equal(result.ingredients[0].amount, "400g"); assert.equal(result.ingredients[2].is_in_inventory, true);
  assert.equal(result.steps[0], "Beat the cream cheese until smooth."); assert.deepEqual(result.step_ingredients[2], []);
});
test("rejects missing instructions, empty instructions and mismatched parallel references", () => {
  for (const patch of [{ steps: [] }, { steps: [""] }, { step_ingredients: [] }, { ingredients: [] }])
    assert.throws(() => normalizeCookingFields({ ...recipe(), ...patch }, missing, covered));
});
test("rejects a step using an ingredient absent from the checklist", () => {
  const data = recipe(); data.step_ingredients[1].push("Ketchup");
  assert.throws(() => normalizeCookingFields(data, missing, covered), /unlisted ingredient/);
});
test("rejects omitted ingredients in either the plan or steps and duplicate names", () => {
  const omitted = recipe(); omitted.ingredients.pop();
  assert.throws(() => normalizeCookingFields(omitted, missing, covered), /omitted a checklist/);
  const unused = recipe(); unused.step_ingredients[1] = ["Eggs"];
  assert.throws(() => normalizeCookingFields(unused, missing, covered), /omit a required ingredient/);
  const duplicate = recipe(); duplicate.ingredients.push({ ...duplicate.ingredients[0] });
  assert.throws(() => normalizeCookingFields(duplicate, missing, covered), /Duplicate recipe ingredient/);
});
test("derives redundant stock flags from the existing checklist and rejects too-small quantities", () => {
  const flag = recipe(); flag.ingredients[0].is_in_inventory = true;
  flag.ingredients[2].is_in_inventory = false;
  const normalized = normalizeCookingFields(flag, missing, covered);
  assert.equal(normalized.ingredients[0].is_in_inventory, false);
  assert.equal(normalized.ingredients[2].is_in_inventory, true);
  const amount = recipe(); amount.ingredients[0].amount = "100g";
  assert.throws(() => normalizeCookingFields(amount, missing, covered), /smaller/);
});
test("bilingual stock names and conservative plurals align without changing checklist rows", () => {
  const data = { ingredients: [{ name: "Salt", amount: "1 tsp", is_in_inventory: false },
    { name: "Limes", amount: "2 pcs", is_in_inventory: true }],
    steps: ["Season with salt and lime."], step_ingredients: [["Salt", "Lime"]] };
  const covered = [{ name: "Salt (盐)", qty_used: "1 tsp" }, { name: "Lime", qty_used: "2 pcs" }];
  const before = JSON.stringify(covered);
  const normalized = normalizeCookingFields(data, [], covered);
  assert.equal(normalized.ingredients[0].name, "Salt (盐)");
  assert.equal(normalized.ingredients[0].is_in_inventory, true);
  assert.deepEqual(normalized.step_ingredients, [["Salt (盐)", "Lime"]]);
  assert.equal(JSON.stringify(covered), before);
});
test("different foods and meaningful variety qualifiers are never fuzzy-matched", () => {
  for (const [name, other] of [["Egg", "Eggplant"], ["Milk", "Coconut milk"], ["Milk", "Milk (skimmed)"], ["Milk", "Milk (skimmed 脱脂)"], ["(盐)", "(糖)"]]) {
    const data = { ingredients: [{ name, amount: "1 pcs", is_in_inventory: false }], steps: ["Use the ingredient."], step_ingredients: [[name]] };
    assert.throws(() => normalizeCookingFields(data, [{ name: other, missing_qty: "1 pcs" }], []), /absent from the checklist/);
  }
});
test("a partially missing item remains missing even if the AI flag says in stock", () => {
  const data = { ingredients: [{ name: "Beef", amount: "500g", is_in_inventory: true }],
    steps: ["Cook the beef."], step_ingredients: [["Beef"]] };
  assert.equal(normalizeCookingFields(data, [{ name: "Beef", missing_qty: "200g" }], [{ name: "Beef", qty_used: "300g" }]).ingredients[0].is_in_inventory, false);
});
test("valid responses need one model call; invalid recipe output gets one correction", async () => {
  let calls = 0;
  const normalize = raw => normalizeCookingFields(raw, missing, covered);
  await generateValidatedChecklist(async correction => { calls++; assert.equal(correction, ""); return recipe(); }, normalize);
  assert.equal(calls, 1);
  calls = 0;
  const fixed = await generateValidatedChecklist(async correction => {
    calls++;
    if (calls === 1) { const invalid = recipe(); invalid.ingredients.pop(); return invalid; }
    assert.match(correction, /failed validation/); assert.match(correction, /same requested dish/);
    return recipe();
  }, normalize);
  assert.equal(calls, 2); assert.equal(fixed.steps.length, 3);
});
test("bad JSON can be corrected and repeated validation failures stop after two calls", async () => {
  let calls = 0;
  await generateValidatedChecklist(async () => ++calls === 1 ? "{bad json}" : JSON.stringify(recipe()),
    raw => normalizeCookingFields(raw, missing, covered));
  assert.equal(calls, 2);
  calls = 0;
  await assert.rejects(generateValidatedChecklist(async () => { calls++; return {}; }, raw => normalizeCookingFields(raw, missing, covered)));
  assert.equal(calls, 2);
});
test("provider/network failures are not mistaken for recipe-format errors", async () => {
  let calls = 0;
  await assert.rejects(generateValidatedChecklist(async () => { calls++; throw new Error("network down"); }, raw => raw), /network down/);
  assert.equal(calls, 1);
});
test("no correction request is started after the request deadline or client disconnect", async () => {
  let calls = 0;
  await assert.rejects(generateValidatedChecklist(async () => { calls++; return {}; },
    raw => normalizeCookingFields(raw, missing, covered), JSON.parse, () => false), error => error.code === "CHECKLIST_RESPONSE_INVALID");
  assert.equal(calls, 1);
});
test("partial shortages retain total recipe quantity and unit normalization", () => {
  const data = { ingredients: [{ name: "Beef", amount: "0.5 kg", is_in_inventory: false }],
    steps: ["Cook the beef through."], step_ingredients: [["Beef"]] };
  assert.equal(normalizeCookingFields(data, [{ name: "Beef", missing_qty: "200g" }], []).ingredients[0].amount, "0.5 kg");
  data.ingredients[0].amount = "0.1 kg";
  assert.throws(() => normalizeCookingFields(data, [{ name: "Beef", missing_qty: "200g" }], []), /smaller/);
});
