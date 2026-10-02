package cn.edu.fudan.dayu.interfaces.rest.v1.catalog;

import java.net.URI;
import java.util.List;

/** 普通用户可见的完整产品说明。 */
public record PublicProductDetailResponse(
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
        List<PublicProductModeResponse> modes,
        String descriptionZh,
        String descriptionEn,
        String sourceDescription,
        URI officialSourceUrl,
        URI colorbarUrl
) {
    public PublicProductDetailResponse {
        modes = List.copyOf(modes);
    }
}
