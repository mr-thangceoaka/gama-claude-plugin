# Chạy plugin với model local (Ollama) — hướng dẫn chi tiết

> Nhánh này chỉ dành cho ai muốn dùng **model free chạy trên máy** (Ollama)
> thay cho Claude subscription. Nếu bạn dùng Claude bình thường thì **bỏ qua**
> — cứ theo `README.md` ở nhánh `main`.

Đọc kỹ phần **"Nó chạy được tới đâu"** ở cuối trước khi kỳ vọng nhiều.

---

## 0. Vì sao phải cài thêm proxy, không cắm thẳng Ollama được?

Agent của plugin thực chất là **Claude Code CLI** (qua `claude-agent-sdk`). CLI này
chỉ biết nói chuyện bằng **Anthropic Messages API** (`/v1/messages`).

Ollama lại phơi ra **OpenAI-compatible API** (`/v1/chat/completions`) — khác định
dạng, nhất là phần gọi tool (tool-use). Nên ở giữa cần một **proxy dịch**
Anthropic ⇄ OpenAI. Ở đây ta dùng [`claude-code-router`](https://github.com/musistudio/claude-code-router)
vì nó sinh ra đúng cho việc này.

```
GAMA plugin ──> ide_agent.py ──> Claude Code CLI
                                     │  (Anthropic /v1/messages)
                                     ▼
                          claude-code-router  (127.0.0.1:3456)
                                     │  (OpenAI /v1/chat/completions)
                                     ▼
                               Ollama (127.0.0.1:11434)
                                     │
                                     ▼
                          model local: qwen2.5-coder:7b
```

---

## 1. Cài Ollama và kéo model

1. Tải Ollama: <https://ollama.com/download> → cài như phần mềm bình thường.
2. Mở terminal, kéo một model **giỏi gọi tool** (bắt buộc, không lấy model chat thường):

   ```bash
   ollama pull qwen2.5-coder:7b
   ```

   - Máy yếu / ít VRAM: cứ `7b`.
   - Máy khỏe (≥16GB VRAM): `qwen2.5-coder:14b` hoặc `:32b` sẽ gọi tool đỡ sai hơn.
3. Kiểm tra Ollama đang chạy (mặc định nó tự chạy nền ở cổng `11434`):

   ```bash
   ollama list
   ```

---

## 2. Cài claude-code-router (proxy)

Cần **Node.js** (tải ở <https://nodejs.org>, bản LTS). Rồi:

```bash
npm install -g @musistudio/claude-code-router
```

Tạo file cấu hình `~/.claude-code-router/config.json`
(trên Windows là `C:\Users\<tên>\.claude-code-router\config.json`):

```json
{
  "Providers": [
    {
      "name": "ollama",
      "api_base_url": "http://127.0.0.1:11434/v1/chat/completions",
      "api_key": "ollama",
      "models": ["qwen2.5-coder:7b"]
    }
  ],
  "Router": {
    "default": "ollama,qwen2.5-coder:7b"
  }
}
```

Chạy proxy (để cửa sổ terminal này mở suốt lúc dùng plugin):

```bash
ccr start
```

Mặc định nó lắng nghe ở `http://127.0.0.1:3456`. Nếu cổng khác, đọc log lúc
`ccr start` in ra và sửa `base_url` ở bước 3 cho khớp.

---

## 3. Trỏ plugin vào proxy

Sửa file `~/.gama-claude.properties` (chỗ đang cấu hình plugin). Bốn dòng quan
trọng:

```properties
# Model local — đúng tên đã pull ở bước 1
model=qwen2.5-coder:7b

# Trỏ vào claude-code-router thay vì api.anthropic.com
base_url=http://127.0.0.1:3456

# Proxy local không cần token thật, nhưng KEY phải khác rỗng
key=dummy

# QUAN TRỌNG: để TRỐNG oauth_token, nếu không agent sẽ ưu tiên đăng nhập Claude
oauth_token=
```

Giữ nguyên `python=` và `script=` như cũ.

> **Cơ chế:** `ide_agent.py` đọc `base_url=` từ file này *trước khi* mặc định về
> `api.anthropic.com`. Để trống `oauth_token` khiến plugin đặt
> `CLAUDE_CODE_OAUTH_TOKEN=""` (rỗng → bị coi là không có) nên `key=dummy` mới
> được dùng làm `ANTHROPIC_API_KEY` gửi cho proxy.

---

## 4. Chạy thử

1. `ccr start` đang chạy (bước 2), Ollama đang chạy (bước 1).
2. Mở GAMA → view **Claude Chat** → gõ "hello" thử.
3. Lần đầu Ollama nạp model vào RAM/VRAM nên **trả lời chậm vài chục giây** — bình thường.

Muốn **quay lại Claude**: xoá dòng `base_url=`, điền lại `oauth_token=` (token
subscription), đổi `model=claude-opus-4-8`, rồi khởi động lại GAMA.

---

## 5. Gỡ lỗi nhanh

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| Chat báo lỗi kết nối / 404 | `ccr start` chưa chạy, hoặc `base_url` sai cổng |
| "model not found" | Tên `model=` không khớp `ollama list`; phải y hệt, kể cả `:7b` |
| Đòi đăng nhập / lỗi 401 | Quên để trống `oauth_token`, hoặc `key=` bị rỗng |
| Trả lời được nhưng **không sửa được file / không gọi tool** | Model quá nhỏ — xem mục dưới |
| Rất chậm | Model to hơn RAM/VRAM → Ollama tràn ra CPU; dùng model nhỏ hơn |

Xem log agent tại `%TEMP%\gama_claude_agent.log` (Windows) để biết chi tiết.

---

## 6. Nó chạy được tới đâu (đọc kỹ)

Plugin này là **agent làm việc**, không chỉ chatbot: nó gọi rất nhiều tool
(validate GAML, chạy headless, đọc console, sim bridge, sửa file + undo…) trong
một vòng lặp nhiều bước.

- Model local **7–8B gọi tool khá tệ** — hay gọi sai tham số hoặc quên gọi. Nên
  **hỏi đáp / giải thích GAML thì ổn**, còn **tự dò lỗi rồi sửa model như Claude
  Opus thì đừng mong**.
- Muốn khá hơn: lên `qwen2.5-coder:14b`/`32b` nếu máy đủ khỏe. Vẫn kém Opus.
- Toàn bộ tính năng "xịn" của plugin (auto-fix, live simulation, snapshot) được
  thiết kế và kiểm thử trên Claude. Ollama là phương án "chữa cháy khi offline /
  không có subscription", không phải thay thế ngang.

Nếu chỉ cần chất lượng, cứ dùng Claude theo `README.md`.
