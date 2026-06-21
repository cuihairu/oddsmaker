# Oddsmaker Unity SDK（C#）

用法
1) 将 `sdks/unity/Runtime/Oddsmaker.cs` 拷贝到你的 Unity 工程（建议 `Assets/Oddsmaker/Runtime/`）
2) 启动时初始化：
```csharp
using Oddsmaker;

void Start() {
  Oddsmaker.Oddsmaker.Init(new Options {
    apiKey = "pk_test_example",
    endpoint = "http://localhost:8080",
    gameId = "game_demo",
    environment = "prod",
    debug = true,
  });
}
```
3) 上报事件：
```csharp
Oddsmaker.Oddsmaker.Track("level_start", new Dictionary<string, object>{{"level",1}});
Oddsmaker.Oddsmaker.SetUserId("u1");
Oddsmaker.Oddsmaker.SetUserProps(new Dictionary<string, object>{{"channel","organic"}});
Oddsmaker.Oddsmaker.Expose("paywall","B");
Oddsmaker.Oddsmaker.Revenue(9.99, "USD", new Dictionary<string, object>{{"sku","noads"}});
Oddsmaker.Oddsmaker.Flush();
```

特性
- 批量发送（默认 5s 或 50 条）、`application/x-ndjson`
- 可选 gzip（`System.IO.Compression.GZipStream`），失败自动回退
- 离线容错：队列持久化到 `Application.persistentDataPath`（ndjson 文件）
- 会话管理：默认 30 分钟闲置切换 `session_id`
- 轻量 JSON 序列化（手写），限制嵌套≤3、数组≤50
- 鉴权：仅 `x-api-key`（客户端 SDK 不支持 HMAC，避免 secret 反编译泄漏）

注意
- 在移动端建议将 `endpoint` 指向你可访问的网关地址（Android 模拟器访问宿主机可用 `http://10.0.2.2:8080`）
- 若你的项目已引入 JSON 库，可自行替换 `ToJson` 实现
- 队列重启后会从 NDJSON 文件恢复，重发时保留原始事件 JSON 与 `props`
