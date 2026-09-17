import Foundation
import XCTest
@testable import Oddsmaker

/// ErrorHandler 直测(纯 Foundation,全公共 API)。
final class ErrorHandlerTests: XCTestCase {

    // ---------- 错误描述 ----------

    func testErrorDescriptionAllCases() {
        XCTAssertEqual(OddsmakerError.notInitialized.errorDescription,
                       "SDK not initialized. Call initSDK() first.")
        XCTAssertEqual(OddsmakerError.invalidURL("http://bad").errorDescription,
                       "Invalid URL: http://bad")
        XCTAssertEqual(OddsmakerError.invalidResponse(statusCode: 502, message: "boom").errorDescription,
                       "Invalid response with status code 502: boom")
        XCTAssertEqual(OddsmakerError.invalidResponse(statusCode: 502, message: nil).errorDescription,
                       "Invalid response with status code 502: unknown error")
        let ns = NSError(domain: "t", code: 1, userInfo: [NSLocalizedDescriptionKey: "ns-desc"])
        XCTAssertTrue(OddsmakerError.networkError(ns).errorDescription!.contains("ns-desc"))
        XCTAssertTrue(OddsmakerError.serializationError(ns).errorDescription!.contains("ns-desc"))
        XCTAssertEqual(OddsmakerError.invalidEventData("bad data").errorDescription,
                       "Invalid event data: bad data")
        XCTAssertEqual(OddsmakerError.queueFull(maxSize: 100).errorDescription,
                       "Event queue is full (max size: 100)")
        XCTAssertEqual(OddsmakerError.rateLimitExceeded(retryAfter: 12).errorDescription,
                       "Rate limit exceeded. Retry after 12 seconds.")
        XCTAssertEqual(OddsmakerError.rateLimitExceeded(retryAfter: nil).errorDescription,
                       "Rate limit exceeded.")
        XCTAssertEqual(OddsmakerError.authenticationFailed(message: "no key").errorDescription,
                       "Authentication failed: no key")
        XCTAssertEqual(OddsmakerError.authenticationFailed(message: nil).errorDescription,
                       "Authentication failed: unknown error")
        XCTAssertEqual(OddsmakerError.permissionDenied(message: nil).errorDescription,
                       "Permission denied: unknown error")
        XCTAssertEqual(OddsmakerError.serverError(statusCode: 500, message: "down").errorDescription,
                       "Server error (500): down")
        XCTAssertEqual(OddsmakerError.timeout.errorDescription, "Request timed out.")
        XCTAssertEqual(OddsmakerError.unknown(nil).errorDescription, "Unknown error: no details")
        XCTAssertTrue(OddsmakerError.unknown(ns).errorDescription!.contains("ns-desc"))

        // failureReason 转发 errorDescription
        XCTAssertEqual(OddsmakerError.timeout.failureReason, "Request timed out.")
    }

    // ---------- 日志级别 ----------

    func testLogLevelComparable() {
        XCTAssertTrue(ErrorHandler.LogLevel.debug < .info)
        XCTAssertTrue(ErrorHandler.LogLevel.info < .warning)
        XCTAssertTrue(ErrorHandler.LogLevel.warning < .error)
        XCTAssertTrue(ErrorHandler.LogLevel.error < .none)
        XCTAssertFalse(ErrorHandler.LogLevel.error < .error)
    }

    // ---------- handle ----------

    func testHandlePassesOddsmakerErrorOrWrapsUnknown() {
        var received: [OddsmakerError] = []
        let h = ErrorHandler(logLevel: .none, errorCallback: { received.append($0) })

        h.handle(OddsmakerError.queueFull(maxSize: 10))
        h.handle(NSError(domain: "t", code: 2))
        XCTAssertEqual(received.count, 2)
        if case .queueFull(let max)? = received.first {
            XCTAssertEqual(max, 10)
        } else {
            XCTFail("expected queueFull, got \(received)")
        }
        if case .unknown(let err)? = received.last {
            XCTAssertNotNil(err)
        } else {
            XCTFail("expected unknown, got \(received)")
        }
    }

    func testHandleReturnsDefaultValue() {
        let h = ErrorHandler(logLevel: .none)
        XCTAssertEqual(h.handle(OddsmakerError.timeout, defaultValue: "fallback"), "fallback")
        XCTAssertEqual(h.handle(OddsmakerError.timeout, defaultValue: 42), 42)
    }

    // ---------- 日志记录与级别过滤 ----------

    func testLogLevelFilteringAndConvenienceMethods() {
        var logs: [(ErrorHandler.LogLevel, String)] = []
        let h = ErrorHandler(logLevel: .warning, logCallback: { level, msg in logs.append((level, msg)) })

        h.debug("dbg")     // 低于 warning → 滤掉
        h.info("inf")      // 低于 warning → 滤掉
        h.warning("warn")  // 过
        h.error("err")     // 过
        XCTAssertEqual(logs.map { $0.1.contains("warn") || $0.1.contains("err") }, [true, true])
        XCTAssertTrue(logs.allSatisfy { $0.1.hasPrefix("[Oddsmaker] [") })
        XCTAssertTrue(logs.allSatisfy { $0.1.contains("[\($0.0)] ") })

        // 级别 .none:全部滤掉
        var none: [String] = []
        let quiet = ErrorHandler(logLevel: .none, logCallback: { _, m in none.append(m) })
        quiet.error("never")
        XCTAssertTrue(none.isEmpty)

        // 级别 .debug:debug 也过
        var all: [ErrorHandler.LogLevel] = []
        let loud = ErrorHandler(logLevel: .debug, logCallback: { l, _ in all.append(l) })
        loud.debug("d"); loud.info("i"); loud.warning("w"); loud.error("e")
        XCTAssertEqual(all, [.debug, .info, .warning, .error])

        // 默认 init
        _ = ErrorHandler()
        // handle 内部也走 log(.error)
        let seen = ErrorHandler(logLevel: .error, logCallback: { _, m in none.append(m) })
        seen.handle(OddsmakerError.timeout)
        XCTAssertEqual(none.count, 1)
    }

    // ---------- 错误分类 ----------

    func testIsNetworkError() {
        let h = ErrorHandler(logLevel: .none)
        XCTAssertTrue(h.isNetworkError(URLError(.notConnectedToInternet)))
        XCTAssertTrue(h.isNetworkError(URLError(.networkConnectionLost)))
        XCTAssertTrue(h.isNetworkError(URLError(.timedOut)))
        XCTAssertTrue(h.isNetworkError(URLError(.cannotFindHost)))
        XCTAssertTrue(h.isNetworkError(URLError(.cannotConnectToHost)))
        XCTAssertFalse(h.isNetworkError(URLError(.badURL)))
        XCTAssertFalse(h.isNetworkError(URLError(.unsupportedURL)))
        XCTAssertTrue(h.isNetworkError(OddsmakerError.networkError(URLError(.timedOut))))
        XCTAssertFalse(h.isNetworkError(OddsmakerError.timeout))
        XCTAssertFalse(h.isNetworkError(NSError(domain: "t", code: 1)))
    }

    func testIsAuthError() {
        let h = ErrorHandler(logLevel: .none)
        XCTAssertTrue(h.isAuthError(OddsmakerError.authenticationFailed(message: nil)))
        XCTAssertTrue(h.isAuthError(OddsmakerError.permissionDenied(message: "denied")))
        XCTAssertTrue(h.isAuthError(OddsmakerError.invalidResponse(statusCode: 401, message: nil)))
        XCTAssertTrue(h.isAuthError(OddsmakerError.invalidResponse(statusCode: 403, message: nil)))
        XCTAssertFalse(h.isAuthError(OddsmakerError.invalidResponse(statusCode: 500, message: nil)))
        XCTAssertFalse(h.isAuthError(OddsmakerError.timeout))
    }

    func testIsRateLimitError() {
        let h = ErrorHandler(logLevel: .none)
        XCTAssertTrue(h.isRateLimitError(OddsmakerError.rateLimitExceeded(retryAfter: 1)))
        XCTAssertTrue(h.isRateLimitError(OddsmakerError.invalidResponse(statusCode: 429, message: nil)))
        XCTAssertFalse(h.isRateLimitError(OddsmakerError.invalidResponse(statusCode: 500, message: nil)))
        XCTAssertFalse(h.isRateLimitError(OddsmakerError.timeout))
    }

    func testIsServerError() {
        let h = ErrorHandler(logLevel: .none)
        XCTAssertTrue(h.isServerError(OddsmakerError.serverError(statusCode: 502, message: nil)))
        XCTAssertTrue(h.isServerError(OddsmakerError.invalidResponse(statusCode: 500, message: nil)))
        XCTAssertFalse(h.isServerError(OddsmakerError.invalidResponse(statusCode: 404, message: nil)))
        XCTAssertFalse(h.isServerError(OddsmakerError.timeout))
    }

    // ---------- 重试建议 ----------

    func testGetRetryDelay() {
        let h = ErrorHandler(logLevel: .none)
        XCTAssertEqual(h.getRetryDelay(OddsmakerError.rateLimitExceeded(retryAfter: 10)), 10)
        XCTAssertEqual(h.getRetryDelay(OddsmakerError.rateLimitExceeded(retryAfter: nil)), 60)
        XCTAssertEqual(h.getRetryDelay(OddsmakerError.networkError(URLError(.notConnectedToInternet))), 5)
        XCTAssertEqual(h.getRetryDelay(OddsmakerError.serverError(statusCode: 500, message: nil)), 30)
        XCTAssertEqual(h.getRetryDelay(OddsmakerError.invalidResponse(statusCode: 503, message: nil)), 30)
        XCTAssertNil(h.getRetryDelay(OddsmakerError.timeout))
        XCTAssertNil(h.getRetryDelay(NSError(domain: "t", code: 1)))
    }
}
