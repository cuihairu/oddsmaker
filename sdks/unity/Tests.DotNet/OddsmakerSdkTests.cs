// Oddsmaker Unity SDK 源码直测(xunit + coverlet)。
// UnityEngine 依赖用 Stubs/UnityEngineStubs.cs 替身;协程由 UnitySim 在测试线程同步推进。
using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Threading;
using Oddsmaker;
using UnityEngine;
using Xunit;
using UnityWebRequest = UnityEngine.Networking.UnityWebRequest;
using ScriptedResponse = UnityEngine.Networking.ScriptedResponse;

public class OddsmakerSdkTests : IDisposable
{
    private const BindingFlags All = BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.Static | BindingFlags.Instance;

    public OddsmakerSdkTests()
    {
        SetInstance(null);
        UnitySim.Reset();
        UnityWebRequest.Reset();
        PlayerPrefs.Reset();
        SystemInfo.Reset();
        Application.Reset();
        Debug.Reset();
        Application.persistentDataPath = Path.Combine(Path.GetTempPath(), "odm-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Application.persistentDataPath);
    }

    public void Dispose() => SetInstance(null);

    // ---------- 测试脚手架 ----------

    private static void SetInstance(Oddsmaker.Oddsmaker instance)
    {
        typeof(Oddsmaker.Oddsmaker).GetProperty("Instance", BindingFlags.Public | BindingFlags.Static)!
            .GetSetMethod(true)!.Invoke(null, new object[] { instance });
    }

    /** 按名称+实参类型匹配反射调用(处理 JField/JValue 重载)。 */
    private static object Invoke(string name, object target, params object[] args)
    {
        var types = args.Select(a => a == null ? null : (object)a.GetType()).ToArray();
        var method = typeof(Oddsmaker.Oddsmaker).GetMethods(All).First(m =>
            m.Name == name &&
            m.GetParameters().Length == args.Length &&
            m.GetParameters().Select(p => p.ParameterType).Zip(types, (pt, at) =>
                at == null ? !pt.IsValueType || Nullable.GetUnderlyingType(pt) != null : pt.IsAssignableFrom((Type)at)).All(ok => ok));
        return method.Invoke(target, args);
    }

    private static string InvokeStr(string name, params object[] args) => (string)Invoke(name, null, args);

    private static Options Opts(Action<Options> mutate = null)
    {
        var o = new Options
        {
            apiKey = "key-1",
            endpoint = "https://ing.example/",
            gameId = "game1",
            environment = "prod",
            deviceId = "dev-explicit",
            flushIntervalSec = 3600,
            maxBatch = 50,
        };
        mutate?.Invoke(o);
        return o;
    }

    private static Oddsmaker.Oddsmaker InitSdk(Action<Options> mutate = null)
    {
        Oddsmaker.Oddsmaker.Init(Opts(mutate));
        return Oddsmaker.Oddsmaker.Instance;
    }

    private static string QueueFile() => Path.Combine(Application.persistentDataPath, "oddsmaker_queue_game1_prod_dev-explicit.ndjson");

    private static string ExperimentCacheFile() => Path.Combine(Application.persistentDataPath, "oddsmaker_experiments_game1_prod.json");

    private static List<string> QueueLines() => File.Exists(QueueFile())
        ? File.ReadAllLines(QueueFile()).Where(l => !string.IsNullOrWhiteSpace(l)).ToList()
        : new List<string>();

    private static string FieldOf(string line, string key) => InvokeStr("JsonString", line, key) ?? "";

    private static byte[] Gunzip(byte[] gz)
    {
        using var ms = new MemoryStream(gz);
        using var inflater = new GZipStream(ms, CompressionMode.Decompress);
        using var outMs = new MemoryStream();
        inflater.CopyTo(outMs);
        return outMs.ToArray();
    }

    // ---------- 纯静态工具(反射直测) ----------

    [Fact]
    public void InferEventTypeCoversAllBranches()
    {
        Assert.Equal("business", InvokeStr("InferEventType", (object)null));          // null → business
        Assert.Equal("identity", InvokeStr("InferEventType", "$identify"));
        Assert.Equal("identity", InvokeStr("InferEventType", "IDENTITY_login"));
        Assert.Equal("risk", InvokeStr("InferEventType", "risk_hit"));
        Assert.Equal("risk", InvokeStr("InferEventType", "fraud_check"));
        Assert.Equal("experiment", InvokeStr("InferEventType", "experiment_view"));
        Assert.Equal("ad", InvokeStr("InferEventType", "rewarded_ad_complete"));
        Assert.Equal("progression", InvokeStr("InferEventType", "level_up"));
        Assert.Equal("progression", InvokeStr("InferEventType", "QUEST_done"));
        Assert.Equal("session", InvokeStr("InferEventType", "session_start"));
        Assert.Equal("error", InvokeStr("InferEventType", "error_bad"));
        Assert.Equal("error", InvokeStr("InferEventType", "crash_now"));
        Assert.Equal("business", InvokeStr("InferEventType", "shop_buy"));
    }

    [Fact]
    public void Hash32KnownVectors()
    {
        Assert.Equal(0x811c9dc5u, (uint)Invoke("Hash32", null, ""));
        Assert.Equal(0xe40c292cu, (uint)Invoke("Hash32", null, "a"));
        Assert.Equal(0xbf9cf968u, (uint)Invoke("Hash32", null, "foobar"));
    }

    [Fact]
    public void AssignVariantRules()
    {
        Assert.Equal("A", Oddsmaker.Oddsmaker.AssignVariant("e", "s", null, "k"));
        Assert.Equal("A", Oddsmaker.Oddsmaker.AssignVariant("e", "s", new List<Tuple<string, int>>(), "k"));
        // weight <= 0 按 1 计
        Assert.Equal("B", Oddsmaker.Oddsmaker.AssignVariant("e", "s",
            new List<Tuple<string, int>> { Tuple.Create("B", 0) }, "k"));
        var variants = new List<Tuple<string, int>> { Tuple.Create("A", 50), Tuple.Create("B", 50) };
        var pick = Oddsmaker.Oddsmaker.AssignVariant("exp", "salt", variants, "u1");
        Assert.Contains(pick, new[] { "A", "B" });
        Assert.Equal(pick, Oddsmaker.Oddsmaker.AssignVariant("exp", "salt", variants, "u1"));   // 确定性
        var pickNoSalt = Oddsmaker.Oddsmaker.AssignVariant("exp", null, variants, "u1");        // salt null → ""
        Assert.Contains(pickNoSalt, new[] { "A", "B" });
    }

    [Fact]
    public void HashUsesSha1TwelveBytes()
    {
        // SHA1("abc") 前 12 字节 = a9993e364706816aba3e2571
        Assert.Equal("d_a9993e364706816aba3e2571", InvokeStr("Hash", "d_", "abc"));
    }

    [Fact]
    public void UuidV7Layout()
    {
        var id = InvokeStr("UuidV7");
        Assert.Equal(36, id.Length);
        var parts = id.Split('-');
        Assert.Equal(new[] { 8, 4, 4, 4, 12 }, parts.Select(p => p.Length));
        Assert.Equal('7', parts[2][0]);   // version 7
        var ts = Convert.ToInt64(parts[0] + parts[1], 16);
        Assert.True(Math.Abs(ts - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) < 10_000, $"ts={ts}");
        Assert.NotEqual(id, InvokeStr("UuidV7"));
    }

    [Fact]
    public void NowMsIsUnixMillisecond()
    {
        var now = (long)Invoke("NowMs", null);
        Assert.True(Math.Abs(now - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) < 5_000);
    }

    [Fact]
    public void JsonStringExtractsValueAndHandlesEdgeCases()
    {
        Assert.Equal("e1", InvokeStr("JsonString", "{\"a\":1,\"event_id\":\"e1\"}", "event_id"));
        Assert.Equal("a\"b", InvokeStr("JsonString", "{\"k\":\"a\\\"b\"}", "k"));   // 转义分支
        Assert.Null(InvokeStr("JsonString", "{\"a\":1}", "missing"));              // 键缺失
        Assert.Null(InvokeStr("JsonString", "{\"k\":123}", "k"));                  // 值非字符串
        Assert.Null(InvokeStr("JsonString", "{\"k\" 1}", "k"));                    // 冒号缺失
        Assert.Null(InvokeStr("JsonString", "{\"k\":\"abc", "k"));                 // 未闭合
    }

    [Fact]
    public void JsonLongParsesAndFallsBackToZero()
    {
        Assert.Equal(1700000000000L, (long)Invoke("JsonLong", null, "{\"ts_client\":1700000000000}", "ts_client"));
        Assert.Equal(-5L, (long)Invoke("JsonLong", null, "{\"k\":-5}", "k"));
        Assert.Equal(0L, (long)Invoke("JsonLong", null, "{\"a\":1}", "missing"));
        Assert.Equal(0L, (long)Invoke("JsonLong", null, "{\"k\":\"xyz\"}", "k"));   // 非数字段
        Assert.Equal(0L, (long)Invoke("JsonLong", null, "{\"k\"}", "k"));           // 无冒号
    }

    [Fact]
    public void JFieldWritesStringLongDouble()
    {
        var sb = new StringBuilder();
        Invoke("JField", null, sb, "k\"ey", "va\"l");
        Assert.Equal("\"k\\\"ey\":\"va\\\"l\",", sb.ToString());

        var sb2 = new StringBuilder();
        Invoke("JField", null, sb2, "n", 5L);
        Assert.Equal("\"n\":5,", sb2.ToString());

        var sb3 = new StringBuilder();
        Invoke("JField", null, sb3, "d", 1.5);
        Assert.Equal("\"d\":1.5,", sb3.ToString());
    }

    [Fact]
    public void JEscapeCoversAllBranches()
    {
        Assert.Equal("", InvokeStr("JEscape", (object)null));
        Assert.Equal("a\\\\b\\\"c\\nd\\re\\tf\\u0001g",
            InvokeStr("JEscape", "a\\b\"c\nd\re\tfg"));
    }

    [Fact]
    public void ToJsonSerializesFullEventWithNestedProps()
    {
        var e = new Event
        {
            event_id = "id1", game_id = "g", environment = "prod", event_type = "business", event_name = "buy",
            user_id = "u", device_id = "d", session_id = "s", ts_client = 1700000000000L,
            platform = "unity", app_version = "1.0", sdk_version = "1.0.0", country = "CN",
            revenue_amount = 9.99, revenue_currency = "USD",
            props = new Dictionary<string, object>
            {
                ["tier"] = "gold",
                ["n"] = 3L,
                ["ok"] = true,
                ["arr"] = new List<object> { 1L, "x" },
                ["sub"] = new Dictionary<string, object> { ["z"] = "v" },
                ["other"] = new Version("1.2.3"),   // default 分支 → ToString
                ["nil"] = null,
            },
        };
        var json = InvokeStr("ToJson", e);
        Assert.Equal(
            "{\"event_id\":\"id1\",\"game_id\":\"g\",\"environment\":\"prod\",\"event_type\":\"business\"," +
            "\"event_name\":\"buy\",\"user_id\":\"u\",\"device_id\":\"d\",\"session_id\":\"s\",\"ts_client\":1700000000000," +
            "\"platform\":\"unity\",\"app_version\":\"1.0\",\"sdk_version\":\"1.0.0\",\"country\":\"CN\"," +
            "\"revenue_amount\":9.99,\"revenue_currency\":\"USD\"," +
            "\"props\":{\"tier\":\"gold\",\"n\":3,\"ok\":true,\"arr\":[1,\"x\"],\"sub\":{\"z\":\"v\"},\"other\":\"1.2.3\",\"nil\":null}}",
            json);
    }

    [Fact]
    public void ToJsonSkipsOptionalFieldsAndHonorsRawJson()
    {
        var e = new Event
        {
            event_id = "id1", game_id = "g", environment = "prod", event_type = "business", event_name = "n",
            device_id = "d", ts_client = 1L,
        };
        var json = InvokeStr("ToJson", e);
        Assert.Equal("{\"event_id\":\"id1\",\"game_id\":\"g\",\"environment\":\"prod\",\"event_type\":\"business\"," +
                     "\"event_name\":\"n\",\"device_id\":\"d\",\"ts_client\":1,\"platform\":\"unity\",\"sdk_version\":\"1.0.0\"}", json);

        e.raw_json = "[raw-passthrough]";
        Assert.Equal("[raw-passthrough]", InvokeStr("ToJson", e));   // raw_json 短路
    }

    [Fact]
    public void JsonDepthAndCountLimits()
    {
        var deep = new Dictionary<string, object>
        {
            ["a"] = new Dictionary<string, object>   // depth 1
            {
                ["b"] = new Dictionary<string, object>   // depth 2
                {
                    ["c"] = new Dictionary<string, object> { ["d"] = "x" },   // depth 3 → 截断 {}
                },
            },
        };
        var sb = new StringBuilder();
        Invoke("JObject", null, sb, "props", deep, 0);
        Assert.Contains("\"a\":{\"b\":{\"c\":{}}}", sb.ToString());

        var deepList = new Dictionary<string, object>
        {
            ["l"] = new List<object> { new List<object> { new List<object> { 1L } } },   // 第 3 层 → []
        };
        var sb2 = new StringBuilder();
        Invoke("JObject", null, sb2, "props", deepList, 0);
        Assert.Contains("\"l\":[[[]]]", sb2.ToString());

        var big = new Dictionary<string, object>();
        for (int i = 0; i < 55; i++) big["k" + i] = i;
        var sb3 = new StringBuilder();
        Invoke("JObject", null, sb3, "props", big, 0);
        var json = sb3.ToString();
        Assert.Contains("\"k49\":49", json);
        Assert.DoesNotContain("\"k50\"", json);   // 50 条截断

        var longList = new Dictionary<string, object> { ["l"] = Enumerable.Range(0, 60).Select(i => (object)(long)i).ToList() };
        var sb4 = new StringBuilder();
        Invoke("JObject", null, sb4, "props", longList, 0);
        Assert.Contains(",48,49]", sb4.ToString());   // 0..49(50 项截断),尾逗号已去
        Assert.DoesNotContain(",50", sb4.ToString());
    }

    [Fact]
    public void BuildNdjsonJoinsLinesAndEstimateSize()
    {
        var a = new Event { event_id = "1", game_id = "g", environment = "p", event_type = "b", event_name = "n1", device_id = "d", ts_client = 1 };
        var b = new Event { event_id = "2", game_id = "g", environment = "p", event_type = "b", event_name = "n2", device_id = "d", ts_client = 2 };
        var two = InvokeStr("BuildNdjson", new List<Event> { a, b });
        Assert.Contains("\n", two);
        Assert.Equal(InvokeStr("ToJson", a) + "\n" + InvokeStr("ToJson", b), two);
        Assert.Equal(InvokeStr("BuildNdjson", new List<Event> { a }), InvokeStr("ToJson", a));   // 单条无换行

        var est = (int)Invoke("EstimateSize", null, a);
        Assert.Equal(Encoding.UTF8.GetByteCount(InvokeStr("ToJson", a)) + 1, est);
    }

    [Fact]
    public void TryGzipProducesValidGzip()
    {
        var method = typeof(Oddsmaker.Oddsmaker).GetMethod("TryGzip", BindingFlags.NonPublic | BindingFlags.Static)!;
        var args = new object[] { Encoding.UTF8.GetBytes("hello gzip") };
        Assert.True((bool)method.Invoke(null, args));
        var gz = (byte[])args[0];
        Assert.Equal(0x1f, gz[0]);
        Assert.Equal(0x8b, gz[1]);
        Assert.Equal("hello gzip", Encoding.UTF8.GetString(Gunzip(gz)));
    }

    [Fact]
    public void FromJsonRestoresValidAndDropsInvalid()
    {
        var line = "{\"event_id\":\"e1\",\"game_id\":\"g\",\"environment\":\"prod\",\"event_type\":\"business\"," +
                   "\"event_name\":\"buy\",\"user_id\":\"u\",\"device_id\":\"d\",\"session_id\":\"s\"," +
                   "\"ts_client\":1700000000000,\"platform\":\"unity\"}";
        var e = (Event)Invoke("FromJson", null, line);
        Assert.NotNull(e);
        Assert.Equal("e1", e.event_id);
        Assert.Equal("u", e.user_id);
        Assert.Equal(1700000000000L, e.ts_client);
        Assert.Equal(line, e.raw_json);   // 原文保留

        Assert.Null((Event)Invoke("FromJson", null, "{\"game_id\":\"g\"}"));   // 缺 event_id
        Assert.Null((Event)Invoke("FromJson", null,
            "{\"event_id\":\"e\",\"game_id\":\"g\",\"environment\":\"p\",\"event_name\":\"n\",\"device_id\":\"d\",\"ts_client\":0}"));   // ts<=0

        var noType = (Event)Invoke("FromJson", null,
            "{\"event_id\":\"e2\",\"game_id\":\"g\",\"environment\":\"p\",\"event_name\":\"level_up\",\"device_id\":\"d\",\"ts_client\":9}");
        Assert.Equal("progression", noType.event_type);   // event_type 缺 → 按事件名推断
    }

    // ---------- 静态入口(未 Init 时安全返回) ----------

    [Fact]
    public void StaticEntriesGuardAgainstUninitializedInstance()
    {
        Assert.Null(Oddsmaker.Oddsmaker.Track("x"));
        Assert.Null(Oddsmaker.Oddsmaker.Identify("u"));
        Assert.Null(Oddsmaker.Oddsmaker.Revenue(1, "USD"));
        Assert.Null(Oddsmaker.Oddsmaker.Expose("e", "A"));
        Assert.Empty(Oddsmaker.Oddsmaker.GetStats());
        Oddsmaker.Oddsmaker.SetUserId("u");            // no-op,不炸
        Oddsmaker.Oddsmaker.SetPlayer("p");
        Oddsmaker.Oddsmaker.SetUserProps(null);
        Oddsmaker.Oddsmaker.SetUserProps(new Dictionary<string, object> { ["k"] = 1 });   // Instance null
        Oddsmaker.Oddsmaker.Flush();                   // no-op
        string cb = null;
        Oddsmaker.Oddsmaker.FetchExperiments("http://c", d => cb = d);   // no-op
        Oddsmaker.Oddsmaker.FetchExperimentsCached("http://c", _ => { });
        Assert.Null(cb);
    }

    // ---------- Init / Configure / deviceId 链 ----------

    [Fact]
    public void InitUsesExplicitDeviceIdFromOptions()
    {
        InitSdk();
        // 显式 deviceId 不落 PlayerPrefs(仅生成路径写);通过队列文件名含 deviceId 证明采用
        Assert.Equal("", PlayerPrefs.GetString("oddsmaker_device_id_game1_prod", ""));
        Assert.Equal(0, PlayerPrefs.SaveCalls);
        Oddsmaker.Oddsmaker.Track("x");
        Assert.True(File.Exists(QueueFile()));   // oddsmaker_queue_game1_prod_dev-explicit.ndjson
    }

    [Fact]
    public void InitFallsBackToPlayerPrefsThenSystemInfoThenGuid()
    {
        // PlayerPrefs 命中
        PlayerPrefs.SetString("oddsmaker_device_id_game1_prod", "dev-saved");
        InitSdk(o => o.deviceId = null);
        Assert.Equal("dev-saved", PlayerPrefs.GetString("oddsmaker_device_id_game1_prod", ""));

        // SystemInfo.deviceUniqueIdentifier → SHA1 前 12 字节
        SetInstance(null);
        PlayerPrefs.Reset();
        SystemInfo.deviceUniqueIdentifier = "abc";
        InitSdk(o => o.deviceId = null);
        Assert.Equal("d_a9993e364706816aba3e2571", PlayerPrefs.GetString("oddsmaker_device_id_game1_prod", ""));   // Hash("d_","abc")

        // SystemInfo 为空 → Guid 兜底
        SetInstance(null);
        PlayerPrefs.Reset();
        SystemInfo.deviceUniqueIdentifier = "";
        InitSdk(o => o.deviceId = null);
        var guid = PlayerPrefs.GetString("oddsmaker_device_id_game1_prod", "");
        Assert.Matches("^d_[0-9a-f]{32}$", guid);
    }

    [Fact]
    public void InitIsIdempotentAndLogsOnce()
    {
        InitSdk();
        var first = Oddsmaker.Oddsmaker.Instance;
        Oddsmaker.Oddsmaker.Init(Opts(o => o.deviceId = "another"));   // 已存在 → 直接返回
        Assert.Same(first, Oddsmaker.Oddsmaker.Instance);
        Assert.Single(Debug.Logs.Where(l => l.Contains("initialized")));
    }

    // ---------- Track / 队列 / 自动 flush ----------

    [Fact]
    public void TrackPersistsQueueAndRestoresAfterRestart()
    {
        var sdk = InitSdk();
        var id1 = Oddsmaker.Oddsmaker.Track("level_start", new Dictionary<string, object> { ["level_id"] = "7" });
        var id2 = Oddsmaker.Oddsmaker.Track("shop_buy");
        Assert.NotEqual(id1, id2);

        var lines = QueueLines();
        Assert.Equal(2, lines.Count);
        Assert.Equal("level_start", FieldOf(lines[0], "event_name"));
        Assert.Equal("progression", FieldOf(lines[0], "event_type"));
        Assert.Equal("business", FieldOf(lines[1], "event_type"));
        Assert.Equal("dev-explicit", FieldOf(lines[0], "device_id"));
        Assert.Equal("unity", FieldOf(lines[0], "platform"));
        Assert.Equal("1.2.3", FieldOf(lines[0], "app_version"));   // Application.version
        Assert.Contains("\"level_id\":\"7\"", lines[0]);
        var stats = Oddsmaker.Oddsmaker.GetStats();
        Assert.Equal(2, (int)stats["queueSize"]);

        // 重启:LoadQueue 从文件恢复
        SetInstance(null);
        InitSdk();
        Assert.Equal(2, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);
    }

    [Fact]
    public void TrackAutoFlushesWhenBatchReached()
    {
        InitSdk(o => { o.maxBatch = 1; });
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok());
        Oddsmaker.Oddsmaker.Track("boot");
        UnitySim.Pump(100);

        Assert.Single(UnityWebRequest.Sent);
        var req = UnityWebRequest.Sent[0];
        Assert.Equal("https://ing.example/v1/batch", req.Url);   // 尾斜杠剥离
        Assert.Equal("POST", req.Verb);
        Assert.Contains(("x-api-key", "key-1"), req.Headers);
        Assert.Contains(("content-type", "application/x-ndjson"), req.Headers);
        Assert.Contains(("x-sdk-version", "unity-1.0.0"), req.Headers);
        Assert.Contains(("content-encoding", "gzip"), req.Headers);
        Assert.Equal(0x1f, req.Body[0]);   // gzip
        var ndjson = Encoding.UTF8.GetString(Gunzip(req.Body));
        Assert.Contains("\"event_name\":\"boot\"", ndjson);
        Assert.Equal(0, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);
        Assert.Equal(1L, Oddsmaker.Oddsmaker.GetStats()["totalEventsSent"]);
        Assert.Equal(1L, Oddsmaker.Oddsmaker.GetStats()["totalFlushAttempts"]);
    }

    [Fact]
    public void TrackAutoFlushesWhenQueueBytesExceeded()
    {
        InitSdk(o => { o.maxQueueBytes = 1; });
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok());
        Oddsmaker.Oddsmaker.Track("big");
        UnitySim.Pump(100);
        Assert.Single(UnityWebRequest.Sent);
        Assert.Equal(0, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);
    }

    [Fact]
    public void FlushWithoutEventsIsNoop()
    {
        InitSdk();
        Oddsmaker.Oddsmaker.Flush();
        UnitySim.Pump(50);
        Assert.Empty(UnityWebRequest.Sent);
    }

    [Fact]
    public void FlushLoopDrainsQueuedEvents()
    {
        InitSdk(o => { o.flushIntervalSec = 1; });
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok());
        Oddsmaker.Oddsmaker.Track("pending");   // maxBatch 50 → 不自动 flush
        UnitySim.Pump(400);                     // FlushLoop 周期 → FlushOnce
        Assert.Single(UnityWebRequest.Sent);
        Assert.Equal(0, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);
    }

    [Fact]
    public void FlushFailureRetriesThreeTimesThenRaisesError()
    {
        InitSdk();
        UnitySim.Reset();   // 丢弃 FlushLoop,避免其反复消耗响应脚本
        var errors = new List<OddsmakerError>();
        Oddsmaker.Oddsmaker.Instance.OnError += errors.Add;
        Oddsmaker.Oddsmaker.Track("lost");
        for (int i = 0; i < 3; i++)   // 三次失败:每次 _retryCount+1 并等待
        {
            UnityWebRequest.Script.Enqueue(ScriptedResponse.Http(500));
            Oddsmaker.Oddsmaker.Flush();
            UnitySim.Pump(60);
            Assert.Empty(errors);
            Assert.Equal(1, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);   // 队列保留
        }
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Http(500));   // 第 4 次:重试额度耗尽
        Oddsmaker.Oddsmaker.Flush();
        UnitySim.Pump(60);

        Assert.Single(errors);
        Assert.Equal("FLUSH_FAILED", errors[0].Code);
        Assert.Contains("500", errors[0].Message);
        Assert.Equal(4, UnityWebRequest.Sent.Count);
        Assert.Equal(4L, Oddsmaker.Oddsmaker.GetStats()["totalEventsFailed"]);
        // 连接失败(ConnectionError)分支:retry 额度刚归零 → 走重试等待而非 OnError
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Dead());
        Oddsmaker.Oddsmaker.Flush();
        UnitySim.Pump(60);
        Assert.Equal(5, UnityWebRequest.Sent.Count);
        Assert.Equal(5L, Oddsmaker.Oddsmaker.GetStats()["totalEventsFailed"]);
        Assert.Single(errors);
    }

    // ---------- 用户与收入事件 ----------

    [Fact]
    public void IdentifyMergesPropsAndTracksPreviousUser()
    {
        InitSdk();
        Assert.NotNull(Oddsmaker.Oddsmaker.Identify("u1"));   // 首次:无 previous_user_id
        Oddsmaker.Oddsmaker.SetPlayer("p9");
        Oddsmaker.Oddsmaker.Identify("u2", new Dictionary<string, object> { ["tier"] = "gold" });
        Oddsmaker.Oddsmaker.Identify("u2");   // 同号:无 previous_user_id

        var lines = QueueLines();
        Assert.Equal(3, lines.Count);
        Assert.Equal("identity", FieldOf(lines[0], "event_type"));
        Assert.DoesNotContain("previous_user_id", lines[0]);
        Assert.Equal("u2", FieldOf(lines[1], "user_id"));
        Assert.Contains("\"previous_user_id\":\"u1\"", lines[1]);
        Assert.Contains("\"player_id\":\"p9\"", lines[1]);
        Assert.Contains("\"new_user_id\":\"u2\"", lines[1]);
        Assert.Contains("\"tier\":\"gold\"", lines[1]);
        Assert.DoesNotContain("previous_user_id", lines[2]);
    }

    [Fact]
    public void UserPropsAndPlayerIdMergeIntoEvents()
    {
        InitSdk();
        Oddsmaker.Oddsmaker.SetUserProps(new Dictionary<string, object> { ["vip"] = 2L });
        Oddsmaker.Oddsmaker.SetPlayer("p1");
        Oddsmaker.Oddsmaker.Track("shop_buy");
        var line = QueueLines()[0];
        Assert.Contains("\"vip\":2", line);
        Assert.Contains("\"player_id\":\"p1\"", line);
    }

    [Fact]
    public void RevenueTracksAmountAndCurrency()
    {
        InitSdk();
        Oddsmaker.Oddsmaker.Revenue(9.99, "USD", new Dictionary<string, object> { ["order_id"] = "o1" });
        var line = QueueLines()[0];
        Assert.Equal("revenue", FieldOf(line, "event_name"));
        Assert.Contains("\"revenue_amount\":9.99", line);
        Assert.Contains("\"revenue_currency\":\"USD\"", line);
        Assert.Contains("\"amount\":9.99", line);
        Assert.Contains("\"order_id\":\"o1\"", line);
        // props == null 分支(Revenue 不带 props)
        Oddsmaker.Oddsmaker.Revenue(1, "USD");
        Assert.Contains("\"amount\":1,", QueueLines()[1]);
    }

    // ---------- 与 Web SDK/后端契约一致性 ----------

    [Fact]
    public void RevenueNormalizesCurrencyToUpperAndSetPlayerEmitsTopLevel()
    {
        InitSdk();
        Oddsmaker.Oddsmaker.SetPlayer("player-1");
        Oddsmaker.Oddsmaker.Revenue(9.99, "usd");
        var line = QueueLines()[0];
        Assert.Equal("revenue", FieldOf(line, "event_name"));
        Assert.Equal("USD", FieldOf(line, "revenue_currency"));   // 币种统一大写(财务按币种分组的口径)
        Assert.Equal("player-1", FieldOf(line, "player_id"));     // 顶层主体(在线/财务指标的主体口径)
    }

    [Fact]
    public void TypedHelpersFillContractTopLevelFields()
    {
        InitSdk();
        Oddsmaker.Oddsmaker.LevelStart("L1");
        Oddsmaker.Oddsmaker.CurrencySource("gem", 5);
        Oddsmaker.Oddsmaker.ItemConsume("sword", 2);
        Oddsmaker.Oddsmaker.IapOrder("order-9", 4.5, "eur");
        Oddsmaker.Oddsmaker.LevelComplete("L1", new Dictionary<string, object> { ["game_mode"] = "pvp" });
        Oddsmaker.Oddsmaker.CurrencySink("gem", 1);
        Oddsmaker.Oddsmaker.AdImpression(0.02, "usd", new Dictionary<string, object> {
            ["network"] = "adcolony", ["placement_id"] = "menu", ["ad_format"] = "rewarded" });
        var lines = QueueLines();
        Assert.Equal(7, lines.Count);
        Assert.Equal("level_start", FieldOf(lines[0], "event_name"));
        Assert.Equal("L1", FieldOf(lines[0], "level_id"));
        Assert.Equal("GEM", FieldOf(lines[1], "resource_id"));
        Assert.Contains("\"resource_amount\":5", lines[1]);
        Assert.Equal("GEM", FieldOf(lines[1], "virtual_currency"));
        Assert.Equal("source", FieldOf(lines[1], "flow_type"));
        Assert.Equal("sword", FieldOf(lines[2], "item_id"));
        Assert.Equal("sink", FieldOf(lines[2], "flow_type"));
        Assert.Equal("order-9", FieldOf(lines[3], "order_id"));
        Assert.Equal("EUR", FieldOf(lines[3], "revenue_currency"));
        Assert.Equal("L1", FieldOf(lines[4], "level_id"));
        Assert.Equal("pvp", FieldOf(lines[4], "game_mode"));   // props 里的 game_mode 同时提升到顶层
        Assert.Equal("sink", FieldOf(lines[5], "flow_type"));
        Assert.Equal("adcolony", FieldOf(lines[6], "ad_network"));
        Assert.Equal("menu", FieldOf(lines[6], "ad_placement"));
        Assert.Equal("rewarded", FieldOf(lines[6], "ad_format"));
        Assert.Equal("USD", FieldOf(lines[6], "revenue_currency"));
    }

    [Fact]
    public void FlushRequeuesKafkaErrorRejectedAndDropsPermanentRejections()
    {
        InitSdk();
        var idRetry = Oddsmaker.Oddsmaker.Track("retryable");
        var idDead = Oddsmaker.Oddsmaker.Track("permanent");
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok(
            "{\"accepted\":[],\"rejected\":[" +
            "{\"event_id\":\"" + idRetry + "\",\"reason\":\"kafka_error\"}," +
            "{\"event_id\":\"" + idDead + "\",\"reason\":\"invalid_schema\"}]}"));
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok());   // 重发成功
        Oddsmaker.Oddsmaker.Flush();
        UnitySim.Pump(100);
        // kafka_error(临时故障)回队后重发,permanent 拒绝不重发
        // (FlushLoop 在 UnitySim 中无延时,回队事件随即被重发,直接断言第二条请求体)
        Assert.Equal(2, UnityWebRequest.Sent.Count);
        var resent = Encoding.UTF8.GetString(Gunzip(UnityWebRequest.Sent[1].Body));
        Assert.Contains(idRetry, resent);
        Assert.DoesNotContain(idDead, resent);
        Assert.Empty(QueueLines());   // 重发成功 → 队列清空
    }

    [Fact]
    public void EndpointSubpathPreservedOnFlush()
    {
        InitSdk(o => { o.endpoint = "https://ing.example/gateway/"; });   // 反代部署带子路径
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok());
        Oddsmaker.Oddsmaker.Track("sub");
        UnitySim.Pump(100);
        Assert.Equal("https://ing.example/gateway/v1/batch", UnityWebRequest.Sent[0].Url);
    }

    [Fact]
    public void RollSessionContinuesWithinGapAndRotatesAfterGap()
    {
        InitSdk();   // sessionGapSec 默认 30*60
        Oddsmaker.Oddsmaker.Track("a");
        Oddsmaker.Oddsmaker.Track("b");
        var lines = QueueLines();
        Assert.Equal(FieldOf(lines[0], "session_id"), FieldOf(lines[1], "session_id"));   // 同会话

        SetInstance(null);
        File.Delete(QueueFile());
        InitSdk(o => { o.sessionGapSec = 0; });
        Oddsmaker.Oddsmaker.Track("a");
        Thread.Sleep(5);
        Oddsmaker.Oddsmaker.Track("b");
        lines = QueueLines();
        Assert.NotEqual(FieldOf(lines[0], "session_id"), FieldOf(lines[1], "session_id"));   // 超时换新
    }

    // ---------- 实验配置 ----------

    [Fact]
    public void FetchExperimentsSuccessAndFailure()
    {
        InitSdk();
        var results = new List<string>();
        var errors = new List<OddsmakerError>();
        Oddsmaker.Oddsmaker.Instance.OnError += errors.Add;

        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok("[{\"id\":\"e1\"}]"));
        Oddsmaker.Oddsmaker.FetchExperiments("https://ctl.example/", results.Add);
        UnitySim.Pump(50);
        Assert.Single(results);
        Assert.Equal("[{\"id\":\"e1\"}]", results[0]);
        Assert.Equal("https://ctl.example/api/config/game1/prod", UnityWebRequest.Sent[0].Url);
        Assert.Contains(("accept", "application/json"), UnityWebRequest.Sent[0].Headers);

        UnityWebRequest.Script.Enqueue(ScriptedResponse.Dead());   // 连接失败分支
        Oddsmaker.Oddsmaker.FetchExperiments("https://ctl.example/", results.Add);
        UnitySim.Pump(50);
        Assert.Equal(2, results.Count);
        Assert.Null(results[1]);   // 失败回调 null
        Assert.Single(errors);
        Assert.Equal("FETCH_EXPERIMENTS_FAILED", errors[0].Code);
    }

    [Fact]
    public void FetchExperimentsCachedHonorsTtlAndRefetchesWhenStale()
    {
        InitSdk();
        var results = new List<string>();

        // 无缓存 → 拉取 + 落缓存文件
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok("[{\"id\":\"fresh\"}]"));
        Oddsmaker.Oddsmaker.FetchExperimentsCached("https://ctl.example/", results.Add);
        UnitySim.Pump(50);
        Assert.Single(UnityWebRequest.Sent);
        Assert.Equal("[{\"id\":\"fresh\"}]", results[0]);
        Assert.True(File.Exists(ExperimentCacheFile()));
        Assert.Contains("\"timestamp\":", File.ReadAllText(ExperimentCacheFile()));

        // TTL 内 → 直接回调缓存 data,不再发请求
        Oddsmaker.Oddsmaker.FetchExperimentsCached("https://ctl.example/", results.Add);
        UnitySim.Pump(50);
        Assert.Equal(2, results.Count);
        Assert.Equal("[{\"id\":\"fresh\"}]", results[1]);
        Assert.Single(UnityWebRequest.Sent);

        // 重启 + 缓存过期(timestamp 过去)→ 重新拉取
        SetInstance(null);
        File.WriteAllText(ExperimentCacheFile(), "{\"timestamp\":1000,\"data\":\"\"}");
        InitSdk();
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok("[{\"id\":\"refetched\"}]"));
        Oddsmaker.Oddsmaker.FetchExperimentsCached("https://ctl.example/", results.Add, ttlSec: 300);
        UnitySim.Pump(50);
        Assert.Equal(3, results.Count);
        Assert.Equal(2, UnityWebRequest.Sent.Count);
        Assert.Equal("[{\"id\":\"refetched\"}]", results[2]);
    }

    // ---------- 生命周期 ----------

    [Fact]
    public void ApplicationPauseAndOnDestroyPersistState()
    {
        InitSdk();
        Oddsmaker.Oddsmaker.Track("keep1");
        Oddsmaker.Oddsmaker.Track("keep2");
        var instance = Oddsmaker.Oddsmaker.Instance;

        Invoke("OnApplicationPause", instance, true);    // 暂停 → 保存
        Assert.True(File.Exists(ExperimentCacheFile()));
        Invoke("OnApplicationPause", instance, false);   // 恢复 → 刷新活跃时间
        Invoke("OnDestroy", instance);                   // 销毁 → 保存
        Assert.Equal(2, QueueLines().Count);
        Assert.Equal(2, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);
    }

    // ---------- 容错分支(catch / 边界) ----------

    [Fact]
    public void SetUserIdAppliesAfterInitAndClears()
    {
        InitSdk();
        Oddsmaker.Oddsmaker.SetUserId("u9");
        Oddsmaker.Oddsmaker.Track("x");
        Oddsmaker.Oddsmaker.SetUserId("");          // 空串 → 置 null
        Oddsmaker.Oddsmaker.Track("y");
        var lines = QueueLines();
        Assert.Equal("u9", FieldOf(lines[0], "user_id"));
        Assert.Null(InvokeStr("JsonString", lines[1], "user_id"));
    }

    [Fact]
    public void LoadQueueToleratesUnreadableFile()
    {
        File.WriteAllText(QueueFile(), "{}");
        File.SetUnixFileMode(QueueFile(), System.IO.UnixFileMode.None);   // chmod 000 → ReadAllLines 抛
        InitSdk();
        Assert.Equal(0, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);   // catch 后队列空
    }

    [Fact]
    public void LoadQueueDropsCorruptLinesAndKeepsValid()
    {
        var valid = "{\"event_id\":\"e9\",\"game_id\":\"g\",\"environment\":\"p\",\"event_type\":\"business\"," +
                    "\"event_name\":\"n\",\"device_id\":\"d\",\"ts_client\":9}";
        File.WriteAllText(QueueFile(), "{not json\n\n" + valid + "\n");
        InitSdk();
        Assert.Equal(1, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);   // 坏行走 FromJson catch,好行保留
    }

    [Fact]
    public void SaveQueueToleratesUnwritablePath()
    {
        Directory.CreateDirectory(QueueFile());   // 路径被目录占用 → WriteAllText 抛
        InitSdk();
        var id = Oddsmaker.Oddsmaker.Track("x");   // 不炸
        Assert.NotEmpty(id);
        Assert.Equal(1, (int)Oddsmaker.Oddsmaker.GetStats()["queueSize"]);   // 内存队列仍在
    }

    [Fact]
    public void ExperimentCacheLoadRestoresTimestampAndToleratesCorruption()
    {
        // 坏 JSON → LoadExperimentCache catch
        File.WriteAllText(ExperimentCacheFile(), "{bad");
        InitSdk();   // 不炸即过

        // 好缓存且 data 非空 → 恢复 timestamp,超长 TTL 内直接命中缓存
        SetInstance(null);
        File.WriteAllText(ExperimentCacheFile(), "{\"data\":\"[]\",\"timestamp\":123}");
        var results = new List<string>();
        InitSdk();
        Oddsmaker.Oddsmaker.FetchExperimentsCached("https://ctl.example/", results.Add, ttlSec: int.MaxValue);   // ~68 年,必命中
        UnitySim.Pump(50);
        Assert.Single(results);
        // 命中缓存 → 回调缓存文件 data 字段的原样内容
        Assert.Equal("[]", results[0]);
        Assert.Empty(UnityWebRequest.Sent);               // 未发请求
    }

    [Fact]
    public void SaveExperimentCacheToleratesUnwritablePath()
    {
        Directory.CreateDirectory(ExperimentCacheFile());   // 路径被目录占用 → WriteAllText 抛
        InitSdk();
        UnitySim.Reset();   // 隔离 FlushLoop
        var results = new List<string>();
        UnityWebRequest.Script.Enqueue(ScriptedResponse.Ok("[{\"id\":\"e1\"}]"));
        Oddsmaker.Oddsmaker.FetchExperimentsCached("https://ctl.example/", results.Add);
        UnitySim.Pump(50);
        Assert.Single(results);                          // save 抛错后回调仍执行
        Assert.Equal("[{\"id\":\"e1\"}]", results[0]);
    }

    [Fact]
    public void AssignVariantFallsThroughOnOverflow()
    {
        // 与服务端 ExperimentSplitterTest 同向量（跨端一致锚定）：溢出形态统一为
        // 无符号取模 + int32 落位 + 兜底末变体
        // [1, MAX]:sum 溢出为 int.MinValue,h = u mod 2^31 ≥ 1 越过前缀和 → 兜底 last（原实现返回首项）
        var two = new List<Tuple<string, int>> { Tuple.Create("A", 1), Tuple.Create("B", int.MaxValue) };
        var r = (int)((uint)Invoke("Hash32", null, "e:s:u1") % 2147483648u);
        Assert.True(r >= 1, $"r={r} 意外命中首段");
        Assert.Equal("B", Oddsmaker.Oddsmaker.AssignVariant("e", "s", two, "u1"));   // 兜底末变体
        // [2, MAX]:sum = -2147483647,u1 的 h=507466947 → 兜底 last
        var twoB = new List<Tuple<string, int>> { Tuple.Create("control", 2), Tuple.Create("treatment", int.MaxValue) };
        Assert.Equal("treatment", Oddsmaker.Oddsmaker.AssignVariant("exp", "salt", twoB, "u1"));
        // 3×MAX:sum 回绕 2147483645 > 0（合法可创建形态）,h=507466949 < MAX → control
        var three = new List<Tuple<string, int>> {
            Tuple.Create("control", int.MaxValue),
            Tuple.Create("treat-a", int.MaxValue),
            Tuple.Create("treat-b", int.MaxValue) };
        Assert.Equal("control", Oddsmaker.Oddsmaker.AssignVariant("exp", "salt", three, "u1"));
        Assert.Equal("control", Oddsmaker.Oddsmaker.AssignVariant("exp", "salt", three, "u8"));
        // [MAX, MAX, 2]:sum 回绕恰为 0,取模无定义 → 兜底 last 不抛 DivideByZeroException（原实现崩溃）
        var zero = new List<Tuple<string, int>> {
            Tuple.Create("control", int.MaxValue),
            Tuple.Create("treat-a", int.MaxValue),
            Tuple.Create("treat-b", 2) };
        Assert.Equal("treat-b", Oddsmaker.Oddsmaker.AssignVariant("exp", "salt", zero, "u1"));
    }

    [Fact]
    public void TryGzipReturnsFalseOnNullInput()
    {
        var method = typeof(Oddsmaker.Oddsmaker).GetMethod("TryGzip", BindingFlags.NonPublic | BindingFlags.Static)!;
        var args = new object[] { null };
        Assert.False((bool)method.Invoke(null, args));   // MemoryStream(null) 抛 → catch → false
    }

    [Fact]
    public void GetStatsReflectsCounters()
    {
        Assert.Empty(Oddsmaker.Oddsmaker.GetStats());   // 未 Init
        InitSdk();
        var stats = Oddsmaker.Oddsmaker.GetStats();
        Assert.Equal(new[] { "queueBytes", "queueSize", "totalEventsFailed", "totalEventsSent", "totalFlushAttempts" },
            stats.Keys.OrderBy(k => k).ToArray());
        Assert.Equal(0L, stats["totalEventsSent"]);
    }
}
