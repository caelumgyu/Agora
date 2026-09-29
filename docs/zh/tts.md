# 文字转语音

打开 **设置 → 多模态 → 文字转语音**，可将 Agora 连接到自托管的 TTS 服务器。该页面支持兼容 OpenAI 语音接口的服务器，特别是通过 [vLLM-Omni](https://recipes.vllm.ai/IndexTeam/IndexTTS-2.5) 部署的 **IndexTTS 2.5**。

## 设置项

| 字段 | 说明 |
|---|---|
| 服务器地址 | TTS 服务器基址，例如 `http://192.168.1.50:8092`。Agora 会调用 `{base}/v1/audio/speech`。 |
| API 密钥 | 可选的 Bearer 令牌（如果服务器要求鉴权）。本地加密存储。 |
| 模型名称 | 服务器期望的模型名，默认 `IndexTeam/IndexTTS-2.5`（留空即使用默认值）。 |
| 音色名称 | 此前通过 `/v1/audio/voices` 上传到服务器的命名音色；优先于参考音频。 |
| 参考音频 | 用于零样本克隆的短参考音频，支持 URL、`data:` URI 或 `file://` 路径；未设置音色名称时必填——IndexTTS 没有内置预设音色。 |
| 语言 | IndexTTS-2.5 支持的语言：`zh`、`en`、`ja`、`es`、`ar`，以及 vLLM-Omni 的中英混合模式 `zhen`。 |
| 语速 | 0.5×–2.0×（模型原生的时长因子，非重采样变速）。 |

**合成测试**按钮会用当前配置合成一段示例并播放，同时可用来验证连通性。

**检测模型与音色**一行会查询服务器（`GET /v1/models` 与 `GET /v1/audio/voices`），把当前可用的模型名和已上传的音色填入对应的下拉框。开启 TTS 或修改服务器 URL 后会自动检测；点击该行可手动重新检测。两个输入框仍支持手填列表之外的值。

**上传本地音频**会把本机录音通过 `/v1/audio/voices` 注册为命名音色，之后可直接按名称选用（服务端要求 1–30 秒、最大 10 MB，同名会覆盖）。这是 IndexTTS/vLLM-Omni 的服务端音色库；DashScope 的系统音色不适用。

## 让模型自己朗读

启用后，Agora 会向支持工具调用的模型提供一个 `speak` 工具。模型在合适的时候调用它，并**自己决定要说的话**——可以是回答的口语化概括，不必逐字念出整条消息。

- 音频合成完成后立即自动播放；该轮回复的消息操作栏会出现 **重放音频** 按钮，可随时重听或停止。
- 自动播放只针对进入会话后新产生的语音，打开旧会话不会自动出声。
- 模型没有调用 `speak` 时不会朗读，也不存在“朗读整条消息”的降级行为。
- 需要变化语气时，模型可在调用里传 `emotion`（如「开心」「疲惫」，或 `auto` 让服务端自行判断）；IndexTTS 服务端支持，DashScope 会忽略。语速始终使用设置里的滑杆值。
- 语气参数额外要求**必须写中文**（分类器只懂中文），这条要求和「每句一次调用」的说明会按服务能力自动附加；你可以在 **高级 → 朗读提示词** 里改写工具说明本身，留空即恢复内置默认。

### 情感控制

- **根据文本自动生成情感**：开启 IndexTTS 的 `use_emo_text`——模型没有单独指定语气时，服务端会从朗读文本推断情感并应用。
- **情感强度**：对应 `emo_alpha`，约 60% 或更低通常比满强度更自然。
- **随机情感**：对应 `use_random`，每次合成随机选择情感，适合试听对比。以上三项仅对 IndexTTS / OpenAI 兼容端点生效。
- 一段回复可以混用多种语气：模型可在同一轮里多次调用 `speak`，每句带各自的语气，播放会按顺序连播（重放按钮会重放整段序列）。
- 合成失败时该轮没有重放按钮，错误会照常显示在工具卡片上。
- 朗读使用设置中的默认音色、语言和语速。

## 云端服务

传输协议由服务器地址自动选择：

- **阿里云百炼 DashScope（千问 TTS）**：地址为 `dashscope.aliyuncs.com` 或 `dashscope-intl.aliyuncs.com`
  时使用 DashScope 原生语音接口。填写模型（默认 `qwen3-tts-flash`）和系统音色（如 `Cherry`）。该接口
  不支持参考音频克隆与语速调节，也不提供模型/音色列表——设置页会显示检测到的接口提示。语言设置会
  映射为 `language_type`（中文、英文、日文、西班牙文；中英混合或无法映射的语种使用 `Auto`）。
- **在 DashScope 上传本地音频**会通过 `qwen-voice-enrollment` 创建复刻音色，并自动把模型切换为
  复刻音色绑定的 `qwen3-tts-vc-2026-01-22`。音频以 base64 Data URI 内联提交，无需公网地址。千问
  复刻要求单声道、采样率 ≥24kHz、推荐 10–20 秒（最长 60 秒、10 MB 以内），且百炼按创建的每个音色计费。
- **其他地址**一律按 OpenAI 兼容的 `/v1/audio/speech` 协议处理：自托管 IndexTTS/vLLM-Omni、聚合网关
  （one-api、new-api、LiteLLM），以及提供该接口的云服务。注意 Agora 始终会发送 IndexTTS 的
  `extra_params` 扩展参数，严格校验未知字段的服务可能需要在前面加一层网关。

## 用 vLLM-Omni 部署 IndexTTS 2.5

```bash
uv venv && source .venv/bin/activate
uv pip install "vllm-omni[indextts2] @ git+https://github.com/vllm-project/vllm-omni.git"

vllm serve IndexTeam/IndexTTS-2.5 \
  --omni \
  --trust-remote-code \
  --port 8092
```

先上传一段参考录音（几秒干净的人声即可），之后在 Agora 中按名称复用：

```bash
curl -X POST http://<host>:8092/v1/audio/voices \
  -F "audio_sample=@/path/to/reference.wav" \
  -F "consent=user-consent-id" \
  -F "name=demo_voice"
```

然后在 Agora 中将 **音色名称** 填为 `demo_voice`；也可以不上传，直接把 **参考音频** 指向内网可访问的音频文件（例如 HTTP URL）。

注意：仅启动 Gradio WebUI 是不够的——Agora 需要 `/v1/audio/speech` 端点，该端点由 vLLM-Omni 提供。其他兼容 OpenAI 语音接口的 TTS 服务器同样可用，只要它们接受 `model`、`input`、`voice`/`ref_audio`、`response_format` 参数并返回原始音频或包含音频 URL / base64 的 JSON。

## 数据流向

文本会发送到你配置的 TTS 服务器；合成得到的音频写入本设备的 Agora 应用私有目录（「合成测试」的样本写入缓存目录）。参见[隐私与安全](privacy.md)。
