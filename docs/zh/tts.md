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

文本会发送到你配置的 TTS 服务器；合成得到的音频写入本设备的 Agora 应用缓存目录。参见[隐私与安全](privacy.md)。
