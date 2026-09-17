import Foundation
@testable import Oddsmaker

/// 脚本化 HTTP 测试服务器(python3 子进程,URLSession 真连)。
/// 响应按请求顺序从 script 文件出队;请求逐行记录到 log 文件。
final class TestServer {
    static let shared = TestServer()

    let url: String
    private let process: Process
    private let scriptFile: URL
    private let logFile: URL

    private init() {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let work = dir.appendingPathComponent(".build/test-server")
        try? FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        let script = work.appendingPathComponent("script.txt")
        let log = work.appendingPathComponent("requests.log")
        FileManager.default.createFile(atPath: script.path, contents: Data())
        FileManager.default.createFile(atPath: log.path, contents: Data())

        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        p.arguments = ["python3", dir.appendingPathComponent("http_stubs.py").path, script.path, log.path]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = Pipe()
        try! p.run()

        // stdout 首行 = 实际监听端口
        var line = Data()
        while let byte = try? out.fileHandleForReading.read(upToCount: 1), !byte.isEmpty {
            line.append(byte)
            if byte == Data([0x0a]) { break }
        }
        guard let text = String(data: line, encoding: .utf8),
              let port = Int(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            fatalError("test server did not report port: \(String(data: line, encoding: .utf8) ?? "nil")")
        }
        process = p
        scriptFile = script
        logFile = log
        url = "http://127.0.0.1:\(port)"
    }

    /// 追加一个脚本响应(按请求顺序出队)。
    func enqueue(status: Int = 200, body: String = "") {
        // ["x"] 序列化后去掉外层方括号,保留字符串的引号与转义
        let bodyJson = String(data: try! JSONSerialization.data(withJSONObject: [body]), encoding: .utf8)!
            .dropFirst().dropLast()
        let line = "{\"status\":\(status),\"body\":\(bodyJson)}\n"
        let h = try! FileHandle(forWritingTo: scriptFile)
        defer { try! h.close() }
        _ = try! h.seekToEnd()
        try! h.write(contentsOf: Data(line.utf8))
    }

    /// 已收到的请求记录(每行一个 JSON:method/path/headers/body)。
    var requests: [[String: Any]] {
        guard let text = try? String(contentsOf: logFile, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").compactMap {
            (try? JSONSerialization.jsonObject(with: Data(String($0).utf8))) as? [String: Any]
        }
    }

    /// 清空响应脚本与请求记录(每个用例 setUp 调)。
    func reset() {
        FileManager.default.createFile(atPath: scriptFile.path, contents: Data())
        FileManager.default.createFile(atPath: logFile.path, contents: Data())
    }
}

/// 轮询等待条件成立(默认 8 秒超时;超时后由调用方断言兜底)。
func waitUntil(timeout: TimeInterval = 8, _ cond: () -> Bool) {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if cond() { return }
        Thread.sleep(forTimeInterval: 0.02)
    }
}
