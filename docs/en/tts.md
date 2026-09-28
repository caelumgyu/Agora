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

The text is sent to the TTS server you configured, and synthesized audio is written to Agora's app cache on this device. See [Privacy & Security](privacy.md).
