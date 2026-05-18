import os
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"
import io
import numpy as np
import torch
import torch.nn as nn
import soundfile as sf
from TTS.api import TTS
from flask import Flask, request, send_file, jsonify

app = Flask(__name__)

class EmbProjector(nn.Module):
    def __init__(self, in_dim=128, out_dim=512):
        super().__init__()
        self.proj = nn.Linear(in_dim, out_dim, bias=False)

    def forward(self, x):
        return self.proj(x)

_projector = EmbProjector(128, 512)
_projector.eval()

def load_emb(npy_path: str) -> np.ndarray:
    emb = np.load(npy_path).astype(np.float32)
    norm = np.linalg.norm(emb)
    if norm > 0:
        emb = emb / norm
    return emb

def project_emb(emb_128: np.ndarray) -> np.ndarray:
    t = torch.FloatTensor(emb_128).unsqueeze(0)  # (1,128)
    with torch.no_grad():
        out = _projector(t)
    result = out.squeeze(0).numpy()
    # L2归一化
    norm = np.linalg.norm(result)
    if norm > 0:
        result = result / norm
    return result

# 加载YourTTS模型
_tts_model = None

def load_your_tts():
    global _tts_model
    if _tts_model is not None:
        return _tts_model

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model_dir = r"./tts_model/"

    _tts_model = TTS(
        model_path=f"{model_dir}\\model_file.pth",
        config_path=f"{model_dir}\\config.json",
    )
    _tts_model.synthesizer.tts_model.to(device)

    print(f"[✓] YourTTS加载完成，运行设备：{device}")
    return _tts_model

#合成wav
def synthesize(
    tts: TTS,
    text: str,
    speaker_emb_512: np.ndarray,
    language: str = "en"
) -> np.ndarray:
    print(f"[✓] 开始合成，文本：{text}")
    synthesizer = tts.synthesizer
    device = next(synthesizer.tts_model.parameters()).device
    text_inputs = synthesizer.tts_model.tokenizer.text_to_ids(text, language=language)
    text_inputs = torch.LongTensor(text_inputs).unsqueeze(0).to(device)
    emb_tensor = torch.FloatTensor(speaker_emb_512).unsqueeze(0).to(device)
    lang2id = synthesizer.tts_model.language_manager.name_to_id
    language_id = torch.LongTensor([lang2id[language]]).to(device)

    with torch.no_grad():
        outputs = synthesizer.tts_model.inference(
            text_inputs,
            aux_input={
                "d_vectors": emb_tensor,
                "language_ids": language_id,
            }
        )

    wav = outputs["model_outputs"][0].squeeze().cpu().numpy()
    peak = np.max(np.abs(wav))
    if peak > 0:
        wav = wav / peak * 0.9

    return wav

#Flask接口
SAMPLE_RATE = 16000

@app.route("/synthesize", methods=["POST"])
def synthesize_api():
    try:
        #读取emb
        emb_bytes = request.files.get("emb")
        if emb_bytes is None:
            return jsonify({"error": "missing emb file"}), 400

        emb_128 = np.frombuffer(emb_bytes.read(), dtype=np.float32)
        if emb_128.shape[0] != 128:
            return jsonify({"error": f"emb dim错误：期望128，实际{emb_128.shape[0]}"}), 400

        #读取text
        text = request.form.get("text", "").strip()
        if not text:
            return jsonify({"error": "missing text"}), 400

        #读取language
        language = request.form.get("language", "en")
        emb_512 = project_emb(emb_128)

        #推理
        tts = load_your_tts()
        wav = synthesize(tts, text, emb_512, language=language)

        # wav返回
        buf = io.BytesIO()
        sf.write(buf, wav, SAMPLE_RATE, format="WAV", subtype="PCM_16")
        buf.seek(0)

        return send_file(
            buf,
            mimetype="audio/wav",
            as_attachment=True,
            download_name="output.wav"
        )

    except Exception as e:
        print(f"[✗] 合成失败：{e}")
        return jsonify({"error": str(e)}), 500

@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"}), 200

# 启动
if __name__ == "__main__":
    print("[*] 预加载YourTTS模型...")
    load_your_tts()
    print("[*] Flask服务启动，监听 0.0.0.0:5000")
    app.run(host="0.0.0.0", port=5000, debug=False)