package cn.edu.fudan.dayu.interfaces.rest.v1.catalog;

import java.util.List;

/** 公开产品目录中的单个产品摘要。 */
public record PublicProductSummaryResponse(
        long productId,
        String code,
        String nameZh,
        String nameEn,
        String family,
        String unit,
        String producer,
        String algorithmName,
        boolean colorbarRequired,
        int sortOrder,
        List<PublicProductModeResponse> modes
) {
    public PublicProductSummaryResponse {
        modes = List.copyOf(modes);
    }
}
