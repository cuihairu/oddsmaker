package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.SymbolMappingEntity;
import io.oddsmaker.control.jpa.SymbolMappingRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 符号化映射文件管理 API
 * 游戏方上传 dSYM/Proguard/source map 的元数据，符号化服务查询使用。
 */
@RestController
@RequestMapping("/api/symbols")
public class SymbolMappingController {

    @Autowired
    private SymbolMappingRepo symbolMappingRepo;

    @PostMapping
    public ResponseEntity<SymbolMappingEntity> register(@RequestBody SymbolMappingEntity body) {
        if (body.id == null || body.id.isEmpty()) {
            body.id = "sym_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
        if (body.status == null) body.status = SymbolMappingEntity.MappingStatus.ACTIVE;
        SymbolMappingEntity saved = symbolMappingRepo.save(body);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<List<SymbolMappingEntity>> listByGame(@PathVariable String gameId) {
        return ResponseEntity.ok(symbolMappingRepo.findActiveByGameId(gameId));
    }

    @GetMapping("/{gameId}/{platform}/{version}")
    public ResponseEntity<List<SymbolMappingEntity>> listByVersion(
            @PathVariable String gameId,
            @PathVariable String platform,
            @PathVariable String version) {
        return ResponseEntity.ok(symbolMappingRepo.findActive(gameId, platform, version));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deprecate(@PathVariable String id) {
        return symbolMappingRepo.findById(id).map(m -> {
            m.status = SymbolMappingEntity.MappingStatus.DEPRECATED;
            symbolMappingRepo.save(m);
            return ResponseEntity.ok(Map.<String, Object>of("id", id, "status", "DEPRECATED"));
        }).orElse(ResponseEntity.notFound().build());
    }
}
