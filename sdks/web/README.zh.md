# Oddsmaker Web SDK

特性
- 批量发送：默认 5s 或 50 条；`application/x-ndjson`；支持 `gzip`（浏览器支持 CompressionStream 时）
- 离线容错：断网缓存到 localStorage，恢复后自动 flush
- 会话管理：默认 30 分钟闲置切会话
- 轻量：无第三方依赖；TypeScript 编写

快速开始
```ts
import { Oddsmaker } from './dist/index.js';

const pt = new Oddsmaker({
  apiKey: 'pk_test_example',
  endpoint: 'http://localhost:8080',
  gameId: 'game_demo',
  environment: 'prod',
});

pt.track('level_start', { level: 3 });
pt.setUserId('u1');
pt.setUserProps({ channel: 'organic' });
pt.expose('paywall', 'B');
pt.revenue(9.99, 'USD', { sku: 'noads' });
await pt.flush();
```

构建
```bash
npm run build
```

一致性测试（哈希/分流/版本比较）
```bash
npm run build && npm run test:hash
```
该测试脚本会验证 FNV-1a 32 的若干向量、分流的确定性，以及 `versionGte`/`versionLte` 的比较语义。其他端（Android/iOS/Unity）需与此一致。
