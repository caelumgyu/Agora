# Text to Speech

Open **Settings → Multimodal → Text to Speech** to connect Agora to a self-hosted TTS server. The settings page supports servers that speak the OpenAI-compatible speech API, in particular **IndexTTS 2.5** served through [vLLM-Omni](https://recipes.vllm.ai/IndexTeam/IndexTTS-2.5).

## Settings

| Field | Meaning |
|---|---|
| Server URL | Base address of the TTS server, e.g. `http://192.168.1.50:8092`. Agora calls `{base}/v1/audio/speech`. |
| API Key | Optional Bearer token, if your server requires authentication. Stored locally, encrypted. |
| Model Name | The model id the server expects. Default `IndexTeam/IndexTTS-2.5` (leave blank to use it). |
| Voice Name | A named voice previously uploaded to the server via `/v1/audio/voices`. Takes precedence over the reference audio. |
| Reference Audio | URL, `data:` URI or `file://` path of a short reference clip used for zero-shot cloning. Required when no voice name is set — IndexTTS has no built-in preset voices. |
| Language | IndexTTS-2.5 language: `zh`, `en`, `ja`, `es`, `ar`, plus the vLLM-Omni mixed mode `zhen` (Chinese + English text). |
| Speed | Speech rate from 0.5× to 2.0× (the model-native duration factor, not post-resampling). |

The **Test Synthesis** button synthesizes a short sample with the current settings and plays it back, which doubles as a connectivity check.

The **Detect models and voices** row queries the server (`GET /v1/models` and `GET /v1/audio/voices`) and fills the Model Name and Voice Name dropdowns with what the server currently offers. Detection runs automatically when TTS is enabled or the server URL changes; tap the row to re-run it manually. Both fields still accept manual entry for values that are not in the list.

**Upload a local audio clip** registers a recording from this device through `/v1/audio/voices` and makes it selectable by name afterwards (the server accepts 1–30 seconds, 10 MB max, and overwrites an existing name). This is the server-side voice library of IndexTTS/vLLM-Omni; DashScope system voices cannot be replaced this way.

## Letting the model speak

Once enabled, Agora offers a `speak` tool to models that support tool calling. The model decides when to use it and **writes the spoken line itself** — usually a speech-friendly summary of the answer rather than the full message read verbatim.

- The audio plays automatically as soon as it is synthesized, and the reply's message action bar gains a **Replay audio** button that can replay or stop it.
- Auto-play only covers speech produced after you opened the conversation; old conversations stay silent until replayed.
- A reply whose model never called `speak` is never read aloud — there is no "read the whole message" fallback.
- When the tone matters, the model can pass `emotion` (a short description such as 开心 or 疲惫, or `auto`); IndexTTS servers honour it, DashScope ignores it. The speaking rate always comes from the settings slider.
- The tone value must be written in **Chinese** — the classifier understands nothing else. That requirement and the "one call per sentence" note are appended automatically; the tool instructions themselves are editable under **Advanced → Speech prompt** (leave it blank to restore the built-in default).
- A reply may mix several sentences: the model can call `speak` multiple times in one turn, each line with its own tone, and they play in order (the replay button replays the whole sequence).
- A failed synthesis leaves no replay button; the error shows on the tool card as usual.
- Speech uses the default voice, language and speed from the settings above.

## Cloud endpoints

The protocol is chosen automatically from the server URL:

- **Qwen-TTS on Alibaba Cloud DashScope** (`dashscope.aliyuncs.com` or `dashscope-intl.aliyuncs.com`)
  uses DashScope's native speech API. Enter the model (default `qwen3-tts-flash`) and a system voice
  such as `Cherry`. This API has no reference-audio cloning and no speed control, and it does not
  expose model/voice listings — the settings page shows the detected endpoint instead. The language
  setting maps to `language_type` (Chinese, English, Japanese, Spanish; mixed or unmapped codes use
  `Auto`).
- **Uploading a local clip on DashScope** creates a cloned voice through `qwen-voice-enrollment` and
  switches the model to `qwen3-tts-vc-2026-01-22`, which the voice is bound to. The sample travels
  inline as a base64 Data URI, so no public host is needed. Qwen-TTS cloning expects mono audio at
  24 kHz or higher, ideally 10–20 seconds (60 s and 10 MB max), and DashScope bills every voice you
  create.
- **Every other address** speaks the OpenAI-compatible `/v1/audio/speech` protocol: self-hosted
  IndexTTS/vLLM-Omni servers, aggregation gateways (one-api, new-api, LiteLLM), and cloud providers
  that offer this endpoint. Note that Agora always sends the IndexTTS `extra_params` extension, so a
  server that rejects unknown request fields may need a small gateway in front.

## Serving IndexTTS 2.5 with vLLM-Omni

```bash
uv venv && source .venv/bin/activate
uv pip install "vllm-omni[indextts2] @ git+https://github.com/vllm-project/vllm-omni.git"

vllm serve IndexTeam/IndexTTS-2.5 \
  --omni \
  --trust-remote-code \
  --port 8092
```

Upload a reference recording once (a few seconds of clean speech is enough), then reuse it by name from Agora:

```bash
curl -X POST http://<host>:8092/v1/audio/voices \
  -F "audio_sample=@/path/to/reference.wav" \
  -F "consent=user-consent-id" \
  -F "name=demo_voice"
```

Then set **Voice Name** to `demo_voice` in Agora, or skip the upload entirely and point **Reference Audio** at a reachable copy of the clip (e.g. an HTTP URL on your LAN).

A plain Gradio WebUI is not enough — Agora needs the `/v1/audio/speech` endpoint, which vLLM-Omni provides. Other OpenAI-compatible TTS servers work as long as they accept `model`, `input`, `voice`/`ref_audio`, `response_format` and return raw audio or a JSON envelope with an audio URL / base64 payload.

## Data flow

The text is sent to the TTS server you configured. Audio synthesized by the **Test Synthesis** button is written to the app cache; speech produced by the `speak` tool is kept in Agora's private app storage so the replay button keeps working. See [Privacy & Security](privacy.md).
