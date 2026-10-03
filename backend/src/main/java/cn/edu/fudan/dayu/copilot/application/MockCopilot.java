package cn.edu.fudan.dayu.copilot.application;

import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.copilot.api.CopilotCommand;
import cn.edu.fudan.dayu.copilot.api.CopilotResponse;
import cn.edu.fudan.dayu.copilot.api.CopilotService;
import cn.edu.fudan.dayu.copilot.api.InterpretedCriteria;
import cn.edu.fudan.dayu.copilot.api.SuggestedAction;
import cn.edu.fudan.dayu.discovery.api.DiscoveryQueryService;
import cn.edu.fudan.dayu.discovery.api.PreviewQuery;
import cn.edu.fudan.dayu.discovery.api.ScientificAssetQuery;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Copilot 在 skeleton Profile 下使用的规则式模拟实现。
 *
 * <p>它用简单关键词模拟自然语言解释，并且只调用 Catalog 和 Discovery 的
 * 公开 API 获取结果，不连接真实 AI 模型。</p>
 */
@Service
@Profile("skeleton")
class MockCopilot implements CopilotService {
    private final CatalogQueryService catalog;
    private final DiscoveryQueryService discovery;

    MockCopilot(CatalogQueryService catalog, DiscoveryQueryService discovery) {
        this.catalog = catalog;
        this.discovery = discovery;
    }

    @Override
    public CopilotResponse query(CopilotCommand command, Optional<ActorContext> actor) {
        catalog.listPublishedProducts();
        String normalized = command.message().toUpperCase();
        boolean precipitation = normalized.contains("PRECIP") || command.message().contains("降水");
        ProductCode product = new ProductCode(precipitation ? "PRECIP" : "BT855");
        if (precipitation) {
            Instant from = Instant.parse("2026-09-02T06:00:00Z");
            Instant to = Instant.parse("2026-09-02T09:00:00Z");
            var result = discovery.searchScientificAssets(new ScientificAssetQuery(product, DataMode.FORECAST,
                    from, to, Instant.parse("2026-09-02T06:00:00Z"), null, new PageRequest(1, 20)));
            return new CopilotResponse("查询PRECIP预报科学数据",
                    new InterpretedCriteria(product, DataMode.FORECAST, from, to, "SCIENTIFIC_ASSET"),
                    "找到" + result.total() + "个真实索引结果。",
                    List.of(new SuggestedAction("APPLY_SCIENTIFIC_SEARCH", "查看检索结果", Map.of(
                            "productCode", "PRECIP", "dataMode", "FORECAST", "from", from.toString(), "to", to.toString()))),
                    false);
        }
        Instant from = Instant.parse("2026-09-26T00:00:00Z");
        Instant to = Instant.parse("2026-09-29T00:00:00Z");
        var frames = discovery.listPreviewFrames(new PreviewQuery(product, DataMode.REALTIME, from, to,
                null, null, 48));
        return new CopilotResponse("查询BT855近期实况预览",
                new InterpretedCriteria(product, DataMode.REALTIME, from, to, "PREVIEW"),
                "找到" + frames.size() + "个真实索引帧。",
                List.of(new SuggestedAction("APPLY_PREVIEW_FILTER", "查看预览", Map.of(
                        "productCode", "BT855", "dataMode", "REALTIME", "from", from.toString(), "to", to.toString()))), false);
    }
}
