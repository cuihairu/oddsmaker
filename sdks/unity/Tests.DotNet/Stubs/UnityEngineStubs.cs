// UnityEngine / UnityEngine.Networking 测试替身:仅覆盖 Oddsmaker.cs 用到的 API 面。
// 协程由 UnitySim 栈式驱动器在测试线程同步推进(AsyncOperation 同步完成、WaitForSeconds 零延迟)。
using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;

namespace UnityEngine
{
    public class Coroutine { }

    public class WaitForSeconds
    {
        public WaitForSeconds(float seconds) { }
    }

    /// <summary>协程调度:StartCoroutine 登记,Pump 同步推进(含嵌套 yield return IEnumerator)。</summary>
    public static class UnitySim
    {
        private sealed class Routine
        {
            public Stack<IEnumerator> Stack = new Stack<IEnumerator>();
            public bool Done;
        }

        private static readonly List<Routine> routines = new List<Routine>();

        public static void Reset() => routines.Clear();

        public static Coroutine StartCoroutine(IEnumerator routine)
        {
            var r = new Routine();
            r.Stack.Push(routine);
            routines.Add(r);
            return new Coroutine();
        }

        /// <summary>推进所有协程 maxSteps 步(死循环协程如 FlushLoop 推到步数上限自然返回)。</summary>
        public static void Pump(int maxSteps = 4000)
        {
            for (int step = 0; step < maxSteps; step++)
            {
                bool any = false;
                foreach (var r in routines.ToArray())
                {
                    if (r.Done) continue;
                    any = true;
                    Step(r);
                }
                if (!any) break;
            }
        }

        private static void Step(Routine r)
        {
            var top = r.Stack.Peek();
            bool moved;
            try { moved = top.MoveNext(); }
            catch { r.Done = true; throw; }
            if (!moved)
            {
                r.Stack.Pop();
                if (r.Stack.Count == 0) r.Done = true;
            }
            else if (top.Current is IEnumerator nested)
            {
                r.Stack.Push(nested);
            }
            // WaitForSeconds / AsyncOperation Current 一律视为已满足,直接下一步。
        }
    }

    public class Object
    {
        public static void DontDestroyOnLoad(Object obj) { }
    }

    public class GameObject : Object
    {
        public string Name;
        public GameObject(string name) { Name = name; }
        public T AddComponent<T>() where T : new() => new T();
    }

    public class MonoBehaviour : Object
    {
        public Coroutine StartCoroutine(IEnumerator routine) => UnitySim.StartCoroutine(routine);
        public void StopAllCoroutines() => UnitySim.Reset();
    }

    public static class PlayerPrefs
    {
        public static readonly Dictionary<string, string> Store = new Dictionary<string, string>();
        public static int SaveCalls;

        public static string GetString(string key, string defaultValue)
            => Store.TryGetValue(key, out var v) ? v : defaultValue;

        public static void SetString(string key, string value) => Store[key] = value;
        public static void Save() => SaveCalls++;

        public static void Reset() { Store.Clear(); SaveCalls = 0; }
    }

    public static class SystemInfo
    {
        public static string deviceUniqueIdentifier = "sim-device-9f2c";
        public static void Reset() => deviceUniqueIdentifier = "sim-device-9f2c";
    }

    public static class Application
    {
        public static string persistentDataPath = ".";
        public static string version = "1.2.3";
        public static void Reset() { persistentDataPath = "."; version = "1.2.3"; }
    }

    public static class Debug
    {
        public static readonly List<string> Logs = new List<string>();
        public static void Log(string message) => Logs.Add(message);
        public static void Reset() => Logs.Clear();
    }

    /// <summary>极简 JsonUtility:仅序列化 ExperimentCache(公共字段)。</summary>
    public static class JsonUtility
    {
        private static readonly JsonSerializerOptions Options = new JsonSerializerOptions { IncludeFields = true };

        public static string ToJson(object obj) => JsonSerializer.Serialize(obj, obj.GetType(), Options);
        public static T FromJson<T>(string json) => JsonSerializer.Deserialize<T>(json, Options);
    }
}

namespace UnityEngine.Networking
{
    public class UnityWebRequestAsyncOperation
    {
        public bool IsDone;
    }

    public class UploadHandler
    {
        public byte[] Body;
    }

    public class UploadHandlerRaw : UploadHandler
    {
        public UploadHandlerRaw(byte[] body) { Body = body; }
    }

    public abstract class DownloadHandler
    {
        public string text;
    }

    public class DownloadHandlerBuffer : DownloadHandler
    {
        public void SetText(string t) { text = t; }
    }

    /// <summary>可编程响应(按请求顺序出队;Code==0 表示连接失败)。</summary>
    public sealed class ScriptedResponse
    {
        public long Code;
        public string Body;
        public static ScriptedResponse Ok(string body = "") => new ScriptedResponse { Code = 200, Body = body };
        public static ScriptedResponse Http(long code) => new ScriptedResponse { Code = code };
        public static ScriptedResponse Dead() => new ScriptedResponse { Code = 0 };
    }

    /// <summary>已发出的请求记录(断言用)。</summary>
    public sealed class SentRequest
    {
        public string Url;
        public string Verb;
        public List<(string, string)> Headers = new List<(string, string)>();
        public byte[] Body;
    }

    public class UnityWebRequest : IDisposable
    {
        public enum Result { InProgress, Success, ConnectionError, ProtocolError, DataProcessingError }

        public const string kHttpVerbPOST = "POST";

        public static readonly List<SentRequest> Sent = new List<SentRequest>();
        public static readonly Queue<ScriptedResponse> Script = new Queue<ScriptedResponse>();

        public string url;
        public long responseCode;
        public string error;
        public Result result = Result.InProgress;
        public UploadHandler uploadHandler;
        public DownloadHandler downloadHandler;

        private readonly string verb;
        private readonly List<(string, string)> headers = new List<(string, string)>();

        public UnityWebRequest(string url, string verb = "GET")
        {
            this.url = url;
            this.verb = verb;
        }

        public static UnityWebRequest Get(string url) =>
            new UnityWebRequest(url, "GET") { downloadHandler = new DownloadHandlerBuffer() };

        public static void Reset() { Sent.Clear(); Script.Clear(); }

        public void SetRequestHeader(string name, string value) => headers.Add((name, value));

        public UnityWebRequestAsyncOperation SendWebRequest()
        {
            var rec = new SentRequest { Url = url, Verb = verb, Headers = headers.ToList(), Body = uploadHandler?.Body };
            Sent.Add(rec);
            var resp = Script.Count > 0 ? Script.Dequeue() : ScriptedResponse.Ok();
            responseCode = resp.Code;
            if (resp.Code >= 200 && resp.Code < 300)
            {
                result = Result.Success;
                error = null;
            }
            else if (resp.Code == 0)
            {
                result = Result.ConnectionError;
                error = "connection refused";
            }
            else
            {
                result = Result.ProtocolError;
                error = "HTTP " + resp.Code;
            }
            (downloadHandler as DownloadHandlerBuffer)?.SetText(resp.Body);
            return new UnityWebRequestAsyncOperation { IsDone = true };   // 同步完成
        }

        public void Dispose() { }
    }
}
