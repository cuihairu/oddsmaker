// Oddsmaker Unity SDK (Runtime) - 持久化缓存层（LAY-015 自 Oddsmaker.cs 拆出，partial class）
// 事件队列落盘/恢复与实验配置缓存读写。

using System;
using System.IO;
using System.Text;
using UnityEngine;

namespace Oddsmaker
{
    public partial class Oddsmaker
    {

        private void LoadQueue()
        {
            try
            {
                if (!File.Exists(QueuePath)) return;
                foreach (var line in File.ReadAllLines(QueuePath))
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    var restored = FromJson(line);
                    if (restored != null) _queue.Add(restored);
                }
                RecalcQueueBytes();
            }
            catch (Exception ex)
            {
                LogDebug($"load queue error: {ex.Message}");
            }
        }

        private void SaveQueue()
        {
            try
            {
                var sb = new StringBuilder(_queue.Count * 128);
                foreach (var e in _queue) sb.AppendLine(ToJson(e));
                File.WriteAllText(QueuePath, sb.ToString());
            }
            catch (Exception ex)
            {
                LogDebug($"save queue error: {ex.Message}");
            }
        }

        private void LoadExperimentCache()
        {
            try
            {
                if (!File.Exists(ExperimentCachePath)) return;
                string json = File.ReadAllText(ExperimentCachePath);
                var cache = JsonUtility.FromJson<ExperimentCache>(json);
                if (cache != null)
                {
                    _experimentCacheTimestamp = cache.timestamp;
                    _experimentCacheData = cache.data ?? "";
                }
            }
            catch (Exception ex)
            {
                LogDebug($"load experiment cache error: {ex.Message}");
            }
        }

        private void SaveExperimentCache()
        {
            try
            {
                var cache = new ExperimentCache
                {
                    timestamp = _experimentCacheTimestamp,
                    data = _experimentCacheData
                };
                string json = JsonUtility.ToJson(cache);
                File.WriteAllText(ExperimentCachePath, json);
            }
            catch (Exception ex)
            {
                LogDebug($"save experiment cache error: {ex.Message}");
            }
        }

        private void RecalcQueueBytes()
        {
            _queueBytes = 0;
            foreach (var e in _queue) _queueBytes += EstimateSize(e);
        }

    }
}
