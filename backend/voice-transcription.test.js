import test from "node:test";
import assert from "node:assert/strict";
import express from "express";
import { registerVoiceTranscription, normalizeVoiceTranscript, validateVoiceAudio } from "./voice-transcription.js";

function voiceBody() {
  const wav = Buffer.alloc(44 + 16000 * 2);
  wav.write("RIFF", 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write("WAVEfmt ", 8);
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(16000, 24); wav.writeUInt32LE(32000, 28); wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34);
  wav.write("data", 36); wav.writeUInt32LE(wav.length - 44, 40);
  return { audio_base64: wav.toString("base64"), mime_type: "audio/wav", item_names: ["Eggs", "Milk"] };
}
const result = (transcript, language) => ({ transcript, language, speech_detected: true, language_confident: true });
async function withServer(generate, block, options = {}) {
  const app = express();
  registerVoiceTranscription(app, { client: { models: { generateContent: generate } }, modelCandidates: ["first", "fallback"], hasApiKey: () => true, ...options });
  app.use(express.json({ limit: "1mb" }));
  app.post("/recipes/generate", (req, res) => res.json(req.body));
  const server = app.listen(0, "127.0.0.1");
  await new Promise(resolve => server.once("listening", resolve));
  const post = (body, path = "/voice/transcribe") => fetch(`http://127.0.0.1:${server.address().port}${path}`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body)
  });
  try { await block(post); } finally { await new Promise(resolve => server.close(resolve)); }
}

test("transcribes English, Malay and Mandarin without translating or generating database actions", async () => {
  for (const expected of [result("Add two eggs", "en-US"), result("Tambah dua botol susu", "ms-MY"), result("我买了两个鸡蛋和一瓶牛奶", "zh-CN")]) {
    await withServer(async request => {
      assert.equal(request.contents[0].parts[2].inlineData.mimeType, "audio/wav");
      assert.equal(request.contents[0].parts[2].inlineData.data, voiceBody().audio_base64);
      assert.match(request.contents[0].parts[0].text, /Do NOT translate/);
      return { text: JSON.stringify({ ...expected, actions: [{ updateInventory: true }] }) };
    }, async post => {
      const response = await post(voiceBody());
      assert.equal(response.status, 200);
      assert.equal(response.headers.get("cache-control"), "no-store");
      assert.deepEqual(await response.json(), expected);
    });
  }
});

test("silence and uncertain language never acquire an invented command or confident preference", () => {
  assert.deepEqual(normalizeVoiceTranscript({ ...result("add two eggs", "en-US"), speech_detected: false }),
    { transcript: "", language: "unknown", speech_detected: false, language_confident: false });
  assert.equal(normalizeVoiceTranscript(result("buy 牛奶", "mixed")).language_confident, false);
  assert.throws(() => normalizeVoiceTranscript({ transcript: "bad", language: "de-DE" }));
  assert.throws(() => normalizeVoiceTranscript(result("a".repeat(2001), "en-US")));
});

test("voice transcription includes non-food scope and preserves household and personal care names", async () => {
  for (const expected of [result("Add two shampoo and one toilet paper", "en-US"),
    result("Tambah dua botol syampu dan satu tisu tandas", "ms-MY"),
    result("添加两瓶洗发水和一包卫生纸", "zh-CN")]) {
    await withServer(async request => {
      const prompt = request.contents[0].parts[0].text;
      assert.match(prompt, /household essentials or personal care/);
      assert.match(prompt, /shampoo, detergent, toilet paper/);
      return { text: JSON.stringify(expected) };
    }, async post => assert.deepEqual(await (await post(voiceBody())).json(), expected));
  }
});

test("rejects malformed, overlong and wrong-format audio before AI", async () => {
  let calls = 0;
  await withServer(async () => { calls++; }, async post => {
    const body = voiceBody();
    const wav = Buffer.from(body.audio_base64, "base64"); wav.writeUInt32LE(48000, 24);
    for (const bad of [{}, { ...body, mime_type: "audio/mp3" }, { ...body, audio_base64: "!bad!" },
      { ...body, audio_base64: wav.toString("base64") }, { ...body, audio_base64: "A".repeat(1024 * 1024 + 4) }]) {
      assert.ok([400, 413].includes((await post(bad)).status));
    }
    assert.equal(calls, 0);
    assert.ok(validateVoiceAudio(body));
  });
});

test("limits untrusted spelling hints and leaves recipes untouched", async () => {
  await withServer(async request => {
    const hints = JSON.parse(request.contents[0].parts[1].text.split(": ").at(-1));
    assert.equal(hints.length, 40);
    assert.ok(hints.every(hint => typeof hint === "string" && hint.length <= 80));
    return { text: JSON.stringify(result("Add 2 eggs", "en-US")) };
  }, async post => {
    await post({ ...voiceBody(), item_names: [null, "", "a".repeat(81), ...Array(100).fill("Eggs")] });
    const recipe = { user_prompt: "noodles for two", available_ingredients: [] };
    assert.deepEqual(await (await post(recipe, "/recipes/generate")).json(), recipe);
  });
});

test("handles missing setup, unavailable models, quota and invalid model output", async () => {
  await withServer(async () => { throw new Error("not called"); }, async post => {
    assert.equal((await post(voiceBody())).status, 503);
  }, { hasApiKey: () => false });
  const models = [];
  await withServer(async request => {
    models.push(request.model);
    if (request.model === "first") throw Object.assign(new Error("not found"), { status: 404 });
    return { text: JSON.stringify(result("Add 2 eggs", "en-US")) };
  }, async post => { assert.equal((await post(voiceBody())).status, 200); });
  assert.deepEqual(models, ["first", "fallback"]);
  await withServer(async () => { throw Object.assign(new Error("quota"), { status: 429 }); }, async post => {
    assert.equal((await post(voiceBody())).status, 429);
  });
  await withServer(async () => ({ text: "not JSON" }), async post => { assert.equal((await post(voiceBody())).status, 502); });
});

test("deadline completes even if the provider ignores abort", async () => {
  await withServer(() => new Promise(() => {}), async post => {
    const response = await post(voiceBody());
    assert.equal(response.status, 504);
    assert.match((await response.json()).error, /timed out/);
  }, { timeoutMs: 20 });
});
