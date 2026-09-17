#!/usr/bin/env python3
"""Oddsmaker iOS SDK 测试辅助:脚本化 HTTP 服务器(供 URLSession 真实连接)。

用法: python3 http_stubs.py <script_file> <log_file>
- 响应队列: script_file 每行一个 JSON {"status":200,"body":"..."},按请求顺序出队;
  队列空时默认 {"status":200,"body":""}
- 请求记录: log_file 每行一个 JSON {method,path,headers,body}
- stdout 首行打印实际监听端口;空闲 300 秒自动退出(防孤儿进程)
"""
import fcntl
import http.server
import json
import os
import socketserver
import sys
import threading
import time

script_file, log_file = sys.argv[1], sys.argv[2]
last_active = time.time()


def next_response():
    with open(script_file, "r+") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        lines = f.read().splitlines()
        resp = json.loads(lines.pop(0)) if lines else {"status": 200, "body": ""}
        f.seek(0)
        f.truncate()
        f.write("\n".join(lines))
        fcntl.flock(f, fcntl.LOCK_UN)
    return resp


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _handle(self):
        global last_active
        last_active = time.time()
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        with open(log_file, "a") as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            f.write(json.dumps({"method": self.command, "path": self.path,
                                "headers": {k.lower(): v for k, v in self.headers.items()},
                                "body": body}) + "\n")
            fcntl.flock(f, fcntl.LOCK_UN)
        r = next_response()
        data = r["body"].encode()
        self.send_response(r["status"])
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_DELETE = _handle

    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def watchdog():
    while True:
        time.sleep(30)
        if time.time() - last_active > 300:
            os._exit(0)


srv = Server(("127.0.0.1", 0), Handler)
print(srv.server_address[1], flush=True)
threading.Thread(target=watchdog, daemon=True).start()
srv.serve_forever()
