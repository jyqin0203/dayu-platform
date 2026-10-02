package cn.edu.fudan.dayu.discovery.application;

import cn.edu.fudan.dayu.discovery.api.ForecastCycleQuery;
import cn.edu.fudan.dayu.discovery.api.ForecastCycleSummary;
import cn.edu.fudan.dayu.discovery.api.PreviewFrame;
import cn.edu.fudan.dayu.discovery.api.PreviewQuery;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.List;
import java.util.Optional;

/** Discovery 的近期 WebP 查询缓存端口；历史 NetCDF 查询不进入此端口。 */
public interface DiscoveryCachePort {
    record Read<T>(long generation, Optional<List<T>> value) {
        public Read {
            value = value.map(List::copyOf);
        }
    }

    Read<PreviewFrame> readPreviewFrames(PreviewQuery query);

    void writePreviewFrames(PreviewQuery query, long generation, List<PreviewFrame> frames);

    Read<ForecastCycleSummary> readForecastCycles(ForecastCycleQuery query);

    void writeForecastCycles(ForecastCycleQuery query, long generation, List<ForecastCycleSummary> cycles);

    void invalidate(ProductCode productCode);
}
